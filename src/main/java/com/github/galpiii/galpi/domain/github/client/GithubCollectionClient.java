package com.github.galpiii.galpi.domain.github.client;

import com.github.galpiii.galpi.domain.collection.config.CollectionProperties;
import com.github.galpiii.galpi.domain.github.client.dto.GithubCommitResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubPullRequestFileResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubPullRequestResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubRepositoryResponse;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.config.GithubClientConfig;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.domain.github.exception.GithubInstallationUnavailableException;
import com.github.galpiii.galpi.domain.github.exception.GithubRateLimitedException;
import com.github.galpiii.galpi.domain.github.exception.GithubRepositoryUnavailableException;
import com.github.galpiii.galpi.global.util.LogSafe;
import com.github.galpiii.galpi.global.util.TokenMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * installation token으로 수집에 필요한 것을 읽는다. 저장소 메타데이터와 PR 데이터다.
 *
 * <p><b>리뷰·댓글 엔드포인트를 부르지 않는다.</b> ERD에 대응 테이블이 없어 저장할 곳이 없고,
 * 안 부르기 때문에 {@code Issues} 권한도 필요 없다. {@code /pulls/{n}/reviews},
 * {@code /pulls/{n}/comments}, {@code /issues/{n}/comments}를 여기에 추가하지 마라.
 *
 * <p>PR 300개면 상세·파일·커밋 세 종류로 최대 900회 호출이다. rate limit 예외는 그대로
 * 위로 올려 워커가 작업을 중단하게 한다 — 여기서 잠들면 안 된다.
 */
@Slf4j
@Component
public class GithubCollectionClient {

    private static final int PER_PAGE = 100;

    private static final ParameterizedTypeReference<List<GithubPullRequestResponse>> PR_LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<GithubPullRequestFileResponse>> FILE_LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<GithubCommitResponse>> COMMIT_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final GithubAppProperties githubProperties;
    private final CollectionProperties collectionProperties;

    public GithubCollectionClient(@Qualifier(GithubClientConfig.API_CLIENT) RestClient restClient,
                                   GithubAppProperties githubProperties,
                                   CollectionProperties collectionProperties) {
        this.restClient = restClient;
        this.githubProperties = githubProperties;
        this.collectionProperties = collectionProperties;
    }

    /**
     * 닫힌 PR 목록 한 페이지.
     *
     * <p>{@code sort=updated&direction=desc}로 받아 병합된 것만 골라낸다. 상한에 도달하면
     * 호출부가 다음 페이지를 요청하지 않는 방식이라, 300개를 채우는 순간 순회가 멈춘다.
     *
     * @param nextUri 이전 페이지의 {@link GithubPage#nextUri()}. 첫 페이지는 {@code null}
     */
    public GithubPage<GithubPullRequestResponse> listClosedPullRequests(String token, String owner,
                                                                        String repo,
                                                                        String nextUri) {
        return listClosedPullRequests(token, owner, repo, nextUri, null);
    }

    public GithubPage<GithubPullRequestResponse> listClosedPullRequests(
            String token, String owner, String repo, String nextUri, RequestBudget budget) {
        String uri = nextUri != null ? nextUri
                : repositoryPath(owner, repo) + "/pulls?state=closed&sort=updated"
                        + "&direction=desc&per_page=" + PER_PAGE;
        return page(uri, token, PR_LIST, budget);
    }

    public GithubPullRequestResponse getPullRequest(String token, String owner, String repo,
                                                    int number) {
        return getPullRequest(token, owner, repo, number, null);
    }

    public GithubPullRequestResponse getPullRequest(String token, String owner, String repo,
                                                    int number, RequestBudget budget) {
        return execute(repositoryPath(owner, repo) + "/pulls/" + number, token,
                spec -> spec.toEntity(GithubPullRequestResponse.class), budget).getBody();
    }

    /** 저장소 스냅샷. default branch가 바뀌었는지도 여기서 확인한다. */
    public GithubRepositoryResponse getRepository(String token, String owner, String repo) {
        GithubRepositoryResponse response = execute(repositoryPath(owner, repo), token,
                spec -> spec.toEntity(GithubRepositoryResponse.class), null).getBody();
        if (response == null) {
            throw new GithubApiException();
        }
        return response;
    }

    /**
     * ref가 가리키는 커밋의 SHA.
     *
     * <p>분석 기준 커밋을 고정하려고 부른다. tarball을 브랜치 이름으로 받으면 받는 사이에
     * 새 커밋이 들어와 "무엇을 분석했는지"가 흐려진다.
     */
    public String getCommitSha(String token, String owner, String repo, String ref) {
        GithubCommitResponse response = execute(
                repositoryPath(owner, repo) + "/commits/"
                        + UriUtils.encodePathSegment(ref, StandardCharsets.UTF_8),
                token, spec -> spec.toEntity(GithubCommitResponse.class), null).getBody();
        if (response == null || response.sha() == null || response.sha().isBlank()) {
            throw new GithubApiException();
        }
        return response.sha();
    }

    /**
     * 변경 파일 전체.
     *
     * <p>GitHub은 PR당 3,000개까지만 준다. 페이지 상한에 걸리면 {@code truncated}가 참이 되고,
     * 호출부가 그 PR을 {@code PARTIAL}로 기록한다.
     */
    public PagedResult<GithubPullRequestFileResponse> listFiles(String token, String owner,
                                                                String repo, int number) {
        return listFiles(token, owner, repo, number, null);
    }

    public PagedResult<GithubPullRequestFileResponse> listFiles(
            String token, String owner, String repo, int number, RequestBudget budget) {
        return collect(repositoryPath(owner, repo) + "/pulls/" + number + "/files?per_page="
                + PER_PAGE, token, FILE_LIST, collectionProperties.maxPullRequestFilePages(), budget);
    }

    public PagedResult<GithubCommitResponse> listCommits(String token, String owner, String repo,
                                                         int number) {
        return listCommits(token, owner, repo, number, null);
    }

    public PagedResult<GithubCommitResponse> listCommits(
            String token, String owner, String repo, int number, RequestBudget budget) {
        return collect(repositoryPath(owner, repo) + "/pulls/" + number + "/commits?per_page="
                + PER_PAGE, token, COMMIT_LIST,
                collectionProperties.maxPullRequestCommitPages(), budget);
    }

    private <T> PagedResult<T> collect(String firstUri, String token,
                                       ParameterizedTypeReference<List<T>> type, int maxPages,
                                       RequestBudget budget) {
        List<T> items = new ArrayList<>();
        String uri = firstUri;
        int pages = 0;

        while (uri != null && pages < maxPages) {
            GithubPage<T> page = page(uri, token, type, budget);
            items.addAll(page.items());
            uri = page.nextUri();
            pages++;
        }
        return new PagedResult<>(List.copyOf(items), uri != null);
    }

    private <T> GithubPage<T> page(String uri, String token,
                                   ParameterizedTypeReference<List<T>> type,
                                   RequestBudget budget) {
        ResponseEntity<List<T>> response = execute(uri, token, spec -> spec.toEntity(type), budget);
        List<T> items = response.getBody() == null ? List.of() : response.getBody();
        String next = LinkHeaderParser.next(response.getHeaders().getFirst(HttpHeaders.LINK))
                .flatMap(this::resolveWithinApi)
                .orElse(null);
        return new GithubPage<>(items, next);
    }

    private <T> ResponseEntity<T> execute(String uri, String token, ResponseExtractor<T> extractor,
                                          RequestBudget budget) {
        try {
            RestClient.RequestHeadersSpec<?> request = restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            if (budget != null) {
                request.attribute(RequestBudget.REQUEST_ATTRIBUTE, budget);
            }
            return extractor.extract(request.retrieve()
                    .onStatus(HttpStatusCode::isError, (ignoredRequest, res) -> {
                        String body = new String(res.getBody().readNBytes(8_192),
                                StandardCharsets.UTF_8);
                        throw toException(uri, res.getStatusCode().value(), res.getHeaders(), body);
                    }));
        } catch (GithubApiException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("[수집] PR 조회 실패 uri={} cause={}",
                    TokenMasker.mask(uri), e.getClass().getSimpleName());
            throw new GithubApiException();
        }
    }

    private RuntimeException toException(String uri, int status, HttpHeaders headers, String body) {
        if (status == 403 && GithubRateLimits.isRateLimited(headers, body)) {
            return new GithubRateLimitedException(GithubRateLimits.retryAfterSeconds(
                    headers, RateLimitSnapshot.from(headers)));
        }
        if (status == 429) {
            return new GithubRateLimitedException(GithubRateLimits.retryAfterSeconds(
                    headers, RateLimitSnapshot.from(headers)));
        }
        if (status == 404 || status == 403) {
            // 저장소가 사라졌거나 이 설치의 범위에서 빠졌다. 이 저장소만 실패로 끝내고
            // 프로젝트의 다른 저장소는 계속 진행한다.
            log.info("[수집] 저장소에 접근할 수 없다 status={} uri={}", status, TokenMasker.mask(uri));
            return new GithubRepositoryUnavailableException();
        }
        if (status == 401) {
            // installation token이 거부됐다. 재발급으로도 풀리지 않는 상태다.
            return new GithubInstallationUnavailableException();
        }
        log.warn("[수집] PR 조회 실패 status={} uri={}", status, TokenMasker.mask(uri));
        return new GithubApiException();
    }

    /**
     * Link 헤더의 다음 페이지가 API 오리진 안인지 확인한다.
     *
     * <p>다음 요청에는 installation token이 Bearer로 붙는다. 응답 헤더 하나로 자격증명을 임의의
     * 호스트에 보내지 않도록 오리진을 강제한다 — 사용자 토큰 쪽과 같은 이유다.
     */
    private Optional<String> resolveWithinApi(String nextUri) {
        URI apiBase;
        URI resolved;
        try {
            apiBase = new URI(githubProperties.apiBaseUrl());
            resolved = apiBase.resolve(new URI(nextUri));
        } catch (URISyntaxException | IllegalArgumentException e) {
            log.warn("[수집] Link 헤더를 URI로 읽을 수 없어 페이지네이션을 멈춘다");
            return Optional.empty();
        }
        if (!sameOrigin(apiBase, resolved)) {
            log.warn("[수집] Link 헤더가 API 오리진 밖을 가리켜 페이지네이션을 멈춘다 host={}",
                    LogSafe.text(resolved.getHost()));
            return Optional.empty();
        }
        return Optional.of(resolved.toString());
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

    /** owner와 repo는 GitHub에서 온 값이지만 경로에 넣기 전에 인코딩한다. */
    private static String repositoryPath(String owner, String repo) {
        return "/repos/" + UriUtils.encodePathSegment(owner, StandardCharsets.UTF_8)
                + "/" + UriUtils.encodePathSegment(repo, StandardCharsets.UTF_8);
    }

    /** @param nextUri 다음 페이지. 없으면 {@code null} */
    public record GithubPage<T>(List<T> items, String nextUri) {
    }

    /** @param truncated 페이지 상한에 걸려 뒤쪽을 못 읽었는지 */
    public record PagedResult<T>(List<T> items, boolean truncated) {
    }

    /** 저장소 하나의 PR 수집이 사용할 수 있는 GitHub 요청 총량. */
    public static final class RequestBudget {

        static final String REQUEST_ATTRIBUTE = RequestBudget.class.getName();
        private int remaining;

        public RequestBudget(int maxRequests) {
            if (maxRequests < 1) {
                throw new IllegalArgumentException("PR 요청 budget은 1 이상이어야 한다");
            }
            this.remaining = maxRequests;
        }

        public int remaining() {
            return remaining;
        }

        void consume() {
            if (remaining == 0) {
                throw new RequestBudgetExceededException();
            }
            remaining--;
        }
    }

    public static final class RequestBudgetExceededException extends RuntimeException {
    }

    @FunctionalInterface
    private interface ResponseExtractor<T> {
        ResponseEntity<T> extract(RestClient.ResponseSpec spec);
    }
}
