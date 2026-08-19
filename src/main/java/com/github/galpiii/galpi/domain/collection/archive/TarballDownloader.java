package com.github.galpiii.galpi.domain.collection.archive;

import com.github.galpiii.galpi.domain.collection.config.CollectionProperties;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.domain.github.exception.GithubInstallationUnavailableException;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.util.LogSafe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * {@code GET /repos/{owner}/{repo}/tarball/{sha}}를 스트리밍으로 내려받는다.
 *
 * <p>이 클래스가 존재하는 이유는 세 가지 방어를 직접 해야 하기 때문이다.
 *
 * <ol>
 *   <li><b>{@code Content-Length}를 믿지 않는다.</b> 헤더가 없거나 거짓일 수 있어, 읽는 동안
 *       실제 바이트를 세면서 상한을 넘는 순간 끊고 지운다. 헤더만 검사하면 헤더에 1KB라고
 *       적고 10GB를 보내는 응답이 그대로 통과한다.</li>
 *   <li><b>리다이렉트 대상이 API 오리진이 아니면 Authorization을 붙이지 않는다.</b> tarball은
 *       {@code codeload.github.com}으로 302를 준다. 실측해 보면 이 호스트에 Authorization을
 *       보내면 404가 나므로, 헤더 전달은 자격증명 유출인 동시에 기능 고장이다. 비공개 저장소의
 *       리다이렉트 URL은 5분짜리 서명 링크라 자격증명을 따로 붙일 필요가 없다.</li>
 *   <li><b>다운그레이드를 막는다.</b> https로 시작한 요청이 http로 리다이렉트되면 거부한다.</li>
 * </ol>
 *
 * <p>JDK 21의 {@code HttpClient}는 리다이렉트에서 Authorization을 스스로 지운다(같은
 * 호스트여도 지운다). 그래도 자동 추종을 쓰지 않는 이유는 그 동작이 문서화된 계약이 아니고,
 * 어차피 바이트 상한과 오리진 검사를 위해 응답을 직접 잡아야 하기 때문이다.
 */
@Slf4j
@Component
public class TarballDownloader {

    private static final String TEMP_FILE_PREFIX = "galpi-tarball-";
    private static final String TEMP_FILE_SUFFIX = ".tar.gz";
    private static final int BUFFER_SIZE = 64 * 1024;

    private final HttpClient httpClient;
    private final GithubAppProperties githubProperties;
    private final CollectionProperties collectionProperties;

    public TarballDownloader(GithubAppProperties githubProperties,
                             CollectionProperties collectionProperties) {
        this.githubProperties = githubProperties;
        this.collectionProperties = collectionProperties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(githubProperties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * @param installationToken 첫 요청에만 붙는다. 리다이렉트 대상에는 붙지 않는다
     * @return 호출부가 반드시 닫아야 하는 임시 파일 핸들
     */
    public DownloadedArchive download(String owner, String repo, String commitSha,
                                      String installationToken) {
        URI apiBase = URI.create(githubProperties.apiBaseUrl());
        URI target = apiBase.resolve(
                trimTrailingSlash(apiBase.getPath()) + "/repos/" + owner + "/" + repo
                        + "/tarball/" + commitSha);

        Path tempFile = createTempFile();
        try {
            long size = follow(target, apiBase, installationToken, tempFile);
            log.debug("[수집] tarball 다운로드 완료 repo={}/{} bytes={}",
                    LogSafe.text(owner), LogSafe.text(repo), size);
            return new DownloadedArchive(tempFile, size);
        } catch (RuntimeException e) {
            deleteQuietly(tempFile);
            throw e;
        }
    }

    private long follow(URI initialTarget, URI apiBase, String installationToken, Path tempFile) {
        URI target = initialTarget;

        for (int hop = 0; hop <= collectionProperties.maxRedirects(); hop++) {
            HttpResponse<InputStream> response = send(target, apiBase, installationToken);
            int status = response.statusCode();

            if (status / 100 == 2) {
                return writeBody(response, tempFile);
            }
            if (!isRedirect(status)) {
                drain(response);
                throw toException(status, target);
            }

            URI next = redirectTarget(response, target);
            drain(response);
            target = next;
        }

        log.warn("[수집] tarball 리다이렉트가 상한({})을 넘었다", collectionProperties.maxRedirects());
        throw new GithubApiException(ErrorCode.COLLECTION_ARCHIVE_DOWNLOAD_FAILED);
    }

    private HttpResponse<InputStream> send(URI target, URI apiBase, String installationToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .timeout(collectionProperties.downloadTimeout())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", githubProperties.apiVersion())
                .header("User-Agent", githubProperties.userAgent())
                .GET();

        // 여기가 이 클래스의 핵심 한 줄이다. 오리진이 다르면 자격증명을 붙이지 않는다.
        if (sameOrigin(apiBase, target)) {
            request.header("Authorization", "Bearer " + installationToken);
        }

        try {
            return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            log.warn("[수집] tarball 요청 실패 host={} cause={}",
                    LogSafe.text(target.getHost()), e.getClass().getSimpleName());
            throw new GithubApiException(ErrorCode.COLLECTION_ARCHIVE_DOWNLOAD_FAILED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GithubApiException(ErrorCode.COLLECTION_ARCHIVE_DOWNLOAD_FAILED);
        }
    }

    /**
     * 읽으면서 센다. 상한을 넘는 순간 스트림을 닫고 임시 파일을 지운다.
     *
     * <p>{@code Files.copy}를 쓰지 않는 이유가 이것이다 — 다 받은 뒤에 크기를 확인하면 이미
     * 디스크를 다 쓴 뒤다.
     */
    private long writeBody(HttpResponse<InputStream> response, Path tempFile) {
        long limit = collectionProperties.maxDownloadBytes();
        long total = 0;
        byte[] buffer = new byte[BUFFER_SIZE];

        try (InputStream body = response.body();
             OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
            int read;
            while ((read = body.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    log.warn("[수집] tarball이 다운로드 상한({} bytes)을 넘어 중단한다", limit);
                    throw new ArchiveLimitExceededException(ErrorCode.COLLECTION_ARCHIVE_TOO_LARGE);
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            log.warn("[수집] tarball 본문을 쓰지 못했다 cause={}", e.getClass().getSimpleName());
            throw new GithubApiException(ErrorCode.COLLECTION_ARCHIVE_DOWNLOAD_FAILED);
        }
        return total;
    }

    private URI redirectTarget(HttpResponse<InputStream> response, URI current) {
        String location = response.headers().firstValue("location")
                .or(() -> response.headers().firstValue("Location"))
                .orElse(null);
        if (location == null || location.isBlank()) {
            log.warn("[수집] tarball 리다이렉트에 Location이 없다");
            throw new GithubApiException(ErrorCode.COLLECTION_ARCHIVE_DOWNLOAD_FAILED);
        }

        URI next;
        try {
            next = current.resolve(new URI(location));
        } catch (URISyntaxException | IllegalArgumentException e) {
            log.warn("[수집] tarball 리다이렉트 Location을 URI로 읽을 수 없다");
            throw new GithubApiException(ErrorCode.COLLECTION_ARCHIVE_DOWNLOAD_FAILED);
        }

        String scheme = next.getScheme() == null ? "" : next.getScheme().toLowerCase(Locale.ROOT);
        boolean downgrade = "https".equalsIgnoreCase(current.getScheme()) && !"https".equals(scheme);
        if (downgrade || !(scheme.equals("https") || scheme.equals("http"))) {
            log.warn("[수집] tarball 리다이렉트가 안전하지 않은 대상을 가리킨다 scheme={}",
                    LogSafe.text(scheme));
            throw new GithubApiException(ErrorCode.COLLECTION_ARCHIVE_DOWNLOAD_FAILED);
        }
        return next;
    }

    private RuntimeException toException(int status, URI target) {
        if (status == 401 || status == 403 || status == 404) {
            log.info("[수집] tarball 접근 거부 status={} host={}",
                    status, LogSafe.text(target.getHost()));
            return new GithubInstallationUnavailableException();
        }
        log.warn("[수집] tarball 다운로드 실패 status={} host={}",
                status, LogSafe.text(target.getHost()));
        return new GithubApiException(ErrorCode.COLLECTION_ARCHIVE_DOWNLOAD_FAILED);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** 리다이렉트 응답 본문은 쓰지 않는다. 닫지 않으면 커넥션이 반환되지 않는다. */
    private static void drain(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            body.readAllBytes();
        } catch (IOException ignored) {
            // 버릴 본문이라 실패해도 할 일이 없다.
        }
    }

    private static boolean sameOrigin(URI base, URI candidate) {
        return candidate.getScheme() != null
                && candidate.getScheme().equalsIgnoreCase(base.getScheme())
                && candidate.getHost() != null
                && candidate.getHost().equalsIgnoreCase(base.getHost())
                && effectivePort(candidate) == effectivePort(base);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return switch (uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT)) {
            case "https" -> 443;
            case "http" -> 80;
            default -> -1;
        };
    }

    /** 이름은 랜덤이다. 사용자 입력에서 파일명을 만들지 않는다. */
    private static Path createTempFile() {
        try {
            return Files.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);
        } catch (IOException e) {
            throw new GithubApiException(ErrorCode.COLLECTION_ARCHIVE_DOWNLOAD_FAILED);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.error("[수집] 실패한 tarball 임시 파일을 지우지 못했다 cause={}",
                    e.getClass().getSimpleName());
        }
    }

    private static String trimTrailingSlash(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
