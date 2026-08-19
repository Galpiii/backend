package com.github.galpiii.galpi.domain.collection.archive;

import com.github.galpiii.galpi.domain.collection.config.CollectionProperties;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.domain.github.exception.GithubInstallationUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 리다이렉트와 다운로드 상한을 로컬 HTTP 서버로 확인한다.
 *
 * <p>실제 GitHub은 {@code api.github.com}에서 {@code codeload.github.com}으로 302를 준다.
 * 실측해 보면 codeload에 Authorization 헤더를 보내면 404가 나므로, 헤더를 넘기지 않는 것은
 * 보안 요구인 동시에 기능 요구다. 여기서는 API 서버와 다운로드 서버를 따로 띄워 그 상황을
 * 재현한다.
 */
@DisplayName("TarballDownloader — 안전 다운로드")
class TarballDownloaderTest {

    private static final String TOKEN = "ghs_installationtokenvalue1234567890";

    private HttpServer apiServer;
    private HttpServer downloadServer;
    private final AtomicReference<String> authorizationAtDownload = new AtomicReference<>();

    @BeforeEach
    void startServers() throws IOException {
        apiServer = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        downloadServer = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        apiServer.start();
        downloadServer.start();
        authorizationAtDownload.set(null);
    }

    @AfterEach
    void stopServers() {
        apiServer.stop(0);
        downloadServer.stop(0);
    }

    @Test
    @DisplayName("다른 호스트로 리다이렉트되면 Authorization 헤더를 전달하지 않는다")
    void doesNotForwardAuthorizationAcrossHosts() throws IOException {
        AtomicReference<String> authorizationAtApi = new AtomicReference<>();
        apiServer.createContext("/repos/octocat/hello/tarball/abc123", exchange -> {
            authorizationAtApi.set(exchange.getRequestHeaders().getFirst("Authorization"));
            redirect(exchange, downloadUrl());
        });
        downloadServer.createContext("/archive", exchange -> {
            authorizationAtDownload.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, "tarball-bytes".getBytes(StandardCharsets.UTF_8));
        });

        try (DownloadedArchive archive = downloader().download("octocat", "hello", "abc123",
                TOKEN)) {
            assertThat(Files.readString(archive.path())).isEqualTo("tarball-bytes");
        }

        assertThat(authorizationAtApi.get()).isEqualTo("Bearer " + TOKEN);
        assertThat(authorizationAtDownload.get()).isNull();
    }

    @Test
    @DisplayName("Content-Length 없이 상한을 넘는 응답을 중간에 끊는다")
    void abortsOversizedResponseWithoutContentLength() {
        apiServer.createContext("/repos/octocat/hello/tarball/abc123",
                exchange -> redirect(exchange, downloadUrl()));
        downloadServer.createContext("/archive", exchange -> {
            // 0을 넘기면 chunked로 나가고 Content-Length 헤더가 붙지 않는다.
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream body = exchange.getResponseBody()) {
                byte[] chunk = new byte[8 * 1024];
                for (int i = 0; i < 64; i++) {
                    body.write(chunk);
                }
            } catch (IOException ignored) {
                // 상한에 걸린 클라이언트가 먼저 끊으면 여기서 깨진다. 그것이 기대하는 동작이다.
            }
        });

        assertThatThrownBy(() -> downloader(DataSize.ofBytes(16 * 1024))
                .download("octocat", "hello", "abc123", TOKEN))
                .isInstanceOf(ArchiveLimitExceededException.class);
    }

    @Test
    @DisplayName("Content-Length가 거짓이어도 실제 읽은 바이트로 막는다")
    void ignoresLyingContentLength() {
        apiServer.createContext("/repos/octocat/hello/tarball/abc123",
                exchange -> redirect(exchange, downloadUrl()));
        downloadServer.createContext("/archive", exchange -> {
            byte[] body = new byte[64 * 1024];
            // 헤더에는 10바이트라고 적고 실제로는 64KB를 보낸다.
            exchange.getResponseHeaders().add("Content-Length", "10");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            } catch (IOException ignored) {
                // 클라이언트가 먼저 끊는다.
            }
        });

        assertThatThrownBy(() -> downloader(DataSize.ofBytes(16 * 1024))
                .download("octocat", "hello", "abc123", TOKEN))
                .isInstanceOf(ArchiveLimitExceededException.class);
    }

    @Test
    @DisplayName("상한에 걸리면 임시 파일을 남기지 않는다")
    void deletesTemporaryFileWhenAborted() throws IOException {
        apiServer.createContext("/repos/octocat/hello/tarball/abc123",
                exchange -> redirect(exchange, downloadUrl()));
        downloadServer.createContext("/archive", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(new byte[64 * 1024]);
            } catch (IOException ignored) {
                // 클라이언트가 먼저 끊는다.
            }
        });

        long before = temporaryTarballCount();
        assertThatThrownBy(() -> downloader(DataSize.ofBytes(1024))
                .download("octocat", "hello", "abc123", TOKEN))
                .isInstanceOf(ArchiveLimitExceededException.class);

        assertThat(temporaryTarballCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("리다이렉트 상한을 넘으면 실패한다")
    void failsAfterTooManyRedirects() {
        apiServer.createContext("/repos/octocat/hello/tarball/abc123",
                exchange -> redirect(exchange, apiUrl() + "/loop"));
        apiServer.createContext("/loop", exchange -> redirect(exchange, apiUrl() + "/loop"));

        assertThatThrownBy(() -> downloader().download("octocat", "hello", "abc123", TOKEN))
                .isInstanceOf(GithubApiException.class);
    }

    @Test
    @DisplayName("404는 저장소 접근 불가로 다룬다")
    void mapsNotFoundToUnavailable() {
        apiServer.createContext("/repos/octocat/hello/tarball/abc123",
                exchange -> respondStatus(exchange, 404));

        assertThatThrownBy(() -> downloader().download("octocat", "hello", "abc123", TOKEN))
                .isInstanceOf(GithubInstallationUnavailableException.class);
    }

    private TarballDownloader downloader() {
        return downloader(DataSize.ofMegabytes(200));
    }

    private TarballDownloader downloader(DataSize maxDownloadSize) {
        return new TarballDownloader(githubProperties(), collectionProperties(maxDownloadSize));
    }

    private String apiUrl() {
        return "http://" + apiServer.getAddress().getHostString() + ":"
                + apiServer.getAddress().getPort();
    }

    private String downloadUrl() {
        return "http://" + downloadServer.getAddress().getHostString() + ":"
                + downloadServer.getAddress().getPort() + "/archive";
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void respond(HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void respondStatus(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static long temporaryTarballCount() throws IOException {
        try (var stream = Files.list(java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir")))) {
            return stream.filter(path -> path.getFileName().toString().startsWith("galpi-tarball-"))
                    .count();
        }
    }

    private GithubAppProperties githubProperties() {
        return new GithubAppProperties("12345", "galpi-test-app", "Iv1.test", "secret",
                "private-key", "http://localhost:8080", "2022-11-28", apiUrl(),
                "https://github.com", "Galpi", List.of("http://localhost:5173"),
                "http://localhost:5173/auth/callback", Duration.ofSeconds(5),
                Duration.ofSeconds(15), 2, 10);
    }

    private static CollectionProperties collectionProperties(DataSize maxDownloadSize) {
        return new CollectionProperties(maxDownloadSize, DataSize.ofGigabytes(1), 20_000,
                DataSize.ofMegabytes(1), DataSize.ofMegabytes(20), 3, Duration.ofSeconds(30),
                30, 30, 30);
    }
}
