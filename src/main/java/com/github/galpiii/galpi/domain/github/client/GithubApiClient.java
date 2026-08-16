package com.github.galpiii.galpi.domain.github.client;

import com.github.galpiii.galpi.domain.github.client.dto.GithubInstallationResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubInstallationsPage;
import com.github.galpiii.galpi.domain.github.client.dto.GithubRepositoriesPage;
import com.github.galpiii.galpi.domain.github.client.dto.GithubRepositoryResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubUserResponse;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.config.GithubClientConfig;
import com.github.galpiii.galpi.domain.github.config.GithubOperationProperties;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.domain.github.exception.GithubInstallationUnavailableException;
import com.github.galpiii.galpi.domain.github.exception.GithubRateLimitedException;
import com.github.galpiii.galpi.domain.github.exception.GithubReauthRequiredException;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.util.LogSafe;
import com.github.galpiii.galpi.global.util.TokenMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@Slf4j
@Component
public class GithubApiClient {

    private final RestClient restClient;
    private final GithubAppProperties properties;
    private final GithubOperationProperties operationProperties;

    public GithubApiClient(@Qualifier(GithubClientConfig.API_CLIENT) RestClient restClient,
                           GithubAppProperties properties,
                           GithubOperationProperties operationProperties) {
        this.restClient = restClient;
        this.properties = properties;
        this.operationProperties = operationProperties;
    }

    public GithubRequestBudget newOperationBudget() {
        return GithubRequestBudget.from(operationProperties);
    }

    public GithubUserResponse getAuthenticatedUser(String userAccessToken) {
        return get("/user", userAccessToken, GithubUserResponse.class).getBody();
    }

    public void revokeUserToken(String userAccessToken) {
        try {
            restClient.method(HttpMethod.DELETE)
                    .uri("/applications/{clientId}/token", properties.clientId())
                    .header(HttpHeaders.AUTHORIZATION, basicCredentials())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("access_token", userAccessToken))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        if (res.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                            return;
                        }
                        throw toRevokeException(res.getStatusCode());
                    })
                    .toBodilessEntity();
        } catch (GithubApiException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("[GitHub] 토큰 폐기 호출 실패 cause={}", e.getClass().getSimpleName());
            throw new GithubApiException();
        }
    }

    private <T> ResponseEntity<T> get(String uri, String token, Class<T> responseType) {
        return execute(uri, token, spec -> spec.toEntity(responseType));
    }

    <T> List<T> getAllPages(String uri,
                            String token,
                            ParameterizedTypeReference<List<T>> pageType,
                            GithubRequestBudget budget) {
        return paginate(uri, token, spec -> spec.toEntity(pageType), body -> body, true, budget);
    }

    /**
     * 페이지 본문이 배열이 아니라 감싼 객체인 엔드포인트용.
     *
     * <p>{@code /user/installations}와 {@code /user/installations/{id}/repositories}가
     * {@code total_count}와 목록을 함께 담은 객체를 돌려준다. Link 헤더 기반 순회는 같으므로
     * 목록을 꺼내는 방법만 받는다.
     */
    private <P, T> List<T> getAllPagesWrapped(String uri,
                                              String token,
                                              Class<P> pageType,
                                              Function<P, List<T>> itemsOf,
                                              boolean partialAllowed,
                                              GithubRequestBudget budget) {
        return paginate(uri, token, spec -> spec.toEntity(pageType),
                body -> body == null ? List.of() : itemsOf.apply(body), partialAllowed, budget);
    }

    /**
     * <p>상한에 걸렸을 때의 처리를 호출 쪽이 정한다. 화면에 뿌리는 목록이라면 잘린 결과라도
     * 없는 것보다 낫지만, 권한 판정에 쓰는 목록이라면 이야기가 다르다. 뒤쪽 페이지에 있던
     * 저장소가 "접근 권한 없음"이 되어 정당한 요청을 403으로 막는다. 조용히 잘린 목록으로
     * 권한을 판단하느니 실패하는 편이 낫다.
     */
    private <B, T> List<T> paginate(String uri,
                                    String token,
                                    ResponseExtractor<B> extractor,
                                    Function<B, List<T>> itemsOf,
                                    boolean partialAllowed,
                                    GithubRequestBudget budget) {
        List<T> collected = new ArrayList<>();
        String nextUri = uri;
        int page = 0;

        while (nextUri != null && page < properties.maxPages()) {
            ResponseEntity<B> response;
            try {
                response = execute(nextUri, token, extractor, budget);
            } catch (GithubApiException e) {
                if (partialAllowed
                        && e.getErrorCode() == ErrorCode.GITHUB_OPERATION_BUDGET_EXCEEDED) {
                    log.warn("[GitHub] 작업 요청 budget 소진. 수집한 페이지까지만 반환 uri={}",
                            TokenMasker.mask(uri));
                    break;
                }
                throw e;
            }
            List<T> items = itemsOf.apply(response.getBody());

            if (items != null) {
                collected.addAll(items);
            }
            page++;
            nextUri = LinkHeaderParser.next(response.getHeaders().getFirst(HttpHeaders.LINK))
                    .flatMap(this::resolveWithinApi)
                    .orElse(null);
        }

        if (nextUri != null) {
            if (!partialAllowed) {
                log.error("[GitHub] 페이지네이션 상한({}) 도달. 잘린 목록으로 권한을 판단할 수 없다. uri={}",
                        properties.maxPages(), TokenMasker.mask(uri));
                throw new GithubApiException(ErrorCode.GITHUB_REPOSITORY_LIST_INCOMPLETE);
            }
            log.warn("[GitHub] 페이지네이션 상한({}) 도달. 이후 페이지는 수집 x. uri={}",
                    properties.maxPages(), TokenMasker.mask(uri));
        }

        return collected;
    }

    /** 설치 범위 ∩ 사용자 접근 권한이 이미 적용된 목록이다. 교집합을 따로 계산하지 마라. */
    public List<GithubInstallationResponse> getUserInstallations(String userAccessToken,
                                                                 GithubRequestBudget budget) {
        return userInstallations(userAccessToken, true, budget);
    }

    public List<GithubRepositoryResponse> getInstallationRepositories(String userAccessToken,
                                                                      Long installationId,
                                                                      GithubRequestBudget budget) {
        return installationRepositories(userAccessToken, installationId, true, budget);
    }

    /**
     * 권한 판정용. 상한에 걸려 목록이 잘리면 예외를 던진다.
     *
     * <p>화면용 installation 조회와 나눈 이유는 잘린 목록의 의미가 다르기 때문이다.
     * 목록 화면은 일부라도 보여주는 편이 낫지만, 이 결과로 "접근할 수 없는 저장소"를 판정하면
     * 뒤쪽 페이지의 정당한 저장소가 403이 된다.
     */
    public List<GithubInstallationResponse> getUserInstallationsComplete(
            String userAccessToken, GithubRequestBudget budget) {
        return userInstallations(userAccessToken, false, budget);
    }

    /** 권한 판정용. 상한에 걸려 목록이 잘리면 예외를 던진다. */
    public List<GithubRepositoryResponse> getInstallationRepositoriesComplete(
            String userAccessToken, Long installationId, GithubRequestBudget budget) {
        return installationRepositories(userAccessToken, installationId, false, budget);
    }

    private List<GithubInstallationResponse> userInstallations(String userAccessToken,
                                                               boolean partialAllowed,
                                                               GithubRequestBudget budget) {
        return getAllPagesWrapped("/user/installations?per_page=100", userAccessToken,
                GithubInstallationsPage.class, GithubInstallationsPage::items, partialAllowed,
                budget);
    }

    private List<GithubRepositoryResponse> installationRepositories(String userAccessToken,
                                                                    Long installationId,
                                                                    boolean partialAllowed,
                                                                    GithubRequestBudget budget) {
        return getAllPagesWrapped(
                "/user/installations/" + installationId + "/repositories?per_page=100",
                userAccessToken, GithubRepositoriesPage.class, GithubRepositoriesPage::items,
                partialAllowed, budget);
    }

    /**
     * Link 헤더의 다음 페이지를 API base에 resolve하고, 오리진이 같을 때만 돌려준다.
     *
     * <p>다음 요청에는 사용자 access token이 Bearer로 붙는다. 검증 없이 따라가면 응답 헤더
     * 하나로 자격증명을 임의의 호스트에 흘릴 수 있다. RestClient는 host가 있는 URI면 baseUrl을
     * 무시하고, followRedirects(NEVER)도 이 경로는 막지 못하므로 여기서 강제한다.
     *
     * <p>비교 전에 resolve하는 이유가 두 가지다. {@code //evil.example/repos} 같은 프로토콜
     * 상대 URL은 scheme이 없어 "절대 URL이 아님"으로 통과해 버리는데, resolve하면
     * {@code https://evil.example/repos}가 되어 오리진 검사에 걸린다. 또 base의 생략된 포트와
     * Link의 명시된 포트({@code :443})를 같은 것으로 보려면 기본 포트를 채워 비교해야 한다.
     */
    private Optional<String> resolveWithinApi(String nextUri) {
        URI apiBase;
        URI resolved;
        try {
            apiBase = new URI(properties.apiBaseUrl());
            resolved = apiBase.resolve(new URI(nextUri));
        } catch (URISyntaxException | IllegalArgumentException e) {
            log.warn("[GitHub] Link 헤더를 URI로 읽을 수 없어 페이지네이션을 멈춘다");
            return Optional.empty();
        }

        if (!sameOrigin(apiBase, resolved)) {
            log.warn("[GitHub] Link 헤더가 API 오리진 밖을 가리켜 페이지네이션을 멈춘다 host={}",
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

    /** 생략된 포트를 scheme의 기본값으로 채운다. https://host 와 https://host:443 은 같은 곳이다. */
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

    private <T> ResponseEntity<T> execute(String uri,
                                          String token,
                                          ResponseExtractor<T> extractor) {
        return execute(uri, token, extractor, newOperationBudget());
    }

    private <T> ResponseEntity<T> execute(String uri,
                                          String token,
                                          ResponseExtractor<T> extractor,
                                          GithubRequestBudget budget) {
        try {
            return extractor.extract(
                    restClient.get()
                            .uri(uri)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .attribute(GithubRequestBudget.REQUEST_ATTRIBUTE, budget)
                            .retrieve()
                            .onStatus(HttpStatusCode::isError, (req, res) -> {
                                String body = new String(res.getBody().readNBytes(8_192),
                                        StandardCharsets.UTF_8);
                                throw toException(uri, res.getStatusCode(), res.getHeaders(), body);
                            }));
        } catch (GithubApiException | GithubReauthRequiredException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("[GitHub] 호출 실패 uri={} cause={}",
                    TokenMasker.mask(uri), e.getClass().getSimpleName());
            throw new GithubApiException();
        }
    }

    private RuntimeException toException(String uri, HttpStatusCode status, HttpHeaders headers,
                                         String body) {
        RateLimitSnapshot snapshot = RateLimitSnapshot.from(headers);
        boolean secondaryLimit = headers.getFirst(HttpHeaders.RETRY_AFTER) != null
                || isSecondaryRateLimit(body);

        if (status.value() == 401) {
            log.info("[GitHub] 401 — user token 만료/무효 uri={}", TokenMasker.mask(uri));
            return new GithubReauthRequiredException();
        }
        if (status.value() == 403 && (snapshot.isExhausted() || secondaryLimit)) {
            log.warn("[GitHub] rate limit uri={} remaining={} resetAt={} retryAfter={}",
                    TokenMasker.mask(uri), snapshot.remaining(), snapshot.resetAt(),
                    headers.getFirst(HttpHeaders.RETRY_AFTER));
            return new GithubRateLimitedException(retryAfterSeconds(headers, snapshot));
        }
        if (status.value() == 429) {
            return new GithubRateLimitedException(retryAfterSeconds(headers, snapshot));
        }
        if (isInstallationRepositoriesUri(uri)
                && (status.value() == 404
                || (status.value() == 403 && isSuspendedInstallation(body)))) {
            return new GithubInstallationUnavailableException();
        }

        log.warn("[GitHub] 호출 실패 status={} uri={}", status.value(), TokenMasker.mask(uri));
        return new GithubApiException();
    }

    private static boolean isSecondaryRateLimit(String body) {
        // GitHub은 secondary limit의 안정적인 machine-readable code를 제공하지 않는다.
        // 호출부가 403으로 먼저 한정한 뒤 공식 영문 메시지를 best-effort로 식별한다.
        if (body == null || body.isBlank()) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("secondary rate limit")
                || normalized.contains("abuse detection mechanism");
    }

    private static boolean isSuspendedInstallation(String body) {
        // 이 403도 별도 code가 없어 repository endpoint로 먼저 한정한 뒤 메시지를 보조로 쓴다.
        // 문구가 바뀌면 일반 GITHUB-005로 실패하며 권한을 잘못 허용하지는 않는다.
        if (body == null || body.isBlank()) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("installation") && normalized.contains("suspend");
    }

    private static long retryAfterSeconds(HttpHeaders headers, RateLimitSnapshot snapshot) {
        String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter != null) {
            try {
                return Math.max(1L, Long.parseLong(retryAfter));
            } catch (NumberFormatException ignored) {
                // GitHub이 정수 초가 아닌 값을 보내면 primary reset 시각이나 보수적 기본값을 쓴다.
            }
        }
        if (snapshot.resetAt() != null) {
            long millis = Duration.between(Instant.now(), snapshot.resetAt()).toMillis();
            return Math.max(1L, (millis + 999L) / 1_000L);
        }
        return 60L;
    }

    private static boolean isInstallationRepositoriesUri(String uri) {
        try {
            String path = URI.create(uri).getPath();
            return path != null && path.matches("/user/installations/\\d+/repositories");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private RuntimeException toRevokeException(HttpStatusCode status) {
        if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
            log.error("[GitHub] 토큰 폐기 401 — GITHUB_CLIENT_ID/SECRET 설정을 확인하세요");
        } else {
            log.warn("[GitHub] 토큰 폐기 실패 status={}", status.value());
        }
        return new GithubApiException();
    }

    private String basicCredentials() {
        String raw = properties.clientId() + ":" + properties.clientSecret();
        return "Basic " + Base64.getEncoder()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface ResponseExtractor<T> {
        ResponseEntity<T> extract(RestClient.ResponseSpec spec);
    }
}
