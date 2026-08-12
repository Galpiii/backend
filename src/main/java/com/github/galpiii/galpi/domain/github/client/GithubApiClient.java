package com.github.galpiii.galpi.domain.github.client;

import com.github.galpiii.galpi.domain.github.client.dto.GithubInstallationResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubInstallationsPage;
import com.github.galpiii.galpi.domain.github.client.dto.GithubRepositoriesPage;
import com.github.galpiii.galpi.domain.github.client.dto.GithubRepositoryResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubUserResponse;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.config.GithubClientConfig;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
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

    public GithubApiClient(@Qualifier(GithubClientConfig.API_CLIENT) RestClient restClient,
                           GithubAppProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
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

    public <T> ResponseEntity<T> get(String uri, String token, Class<T> responseType) {
        return execute(uri, token, spec -> spec.toEntity(responseType));
    }

    public <T> List<T> getAllPages(String uri, String token, ParameterizedTypeReference<List<T>> pageType) {
        return paginate(uri, token, spec -> spec.toEntity(pageType), body -> body);
    }

    /**
     * 페이지 본문이 배열이 아니라 감싼 객체인 엔드포인트용.
     *
     * <p>{@code /user/installations}와 {@code /user/installations/{id}/repositories}가
     * {@code total_count}와 목록을 함께 담은 객체를 돌려준다. Link 헤더 기반 순회는 같으므로
     * 목록을 꺼내는 방법만 받는다.
     */
    public <P, T> List<T> getAllPagesWrapped(String uri,
                                             String token,
                                             Class<P> pageType,
                                             Function<P, List<T>> itemsOf) {
        return paginate(uri, token, spec -> spec.toEntity(pageType),
                body -> body == null ? List.of() : itemsOf.apply(body));
    }

    private <B, T> List<T> paginate(String uri,
                                    String token,
                                    ResponseExtractor<B> extractor,
                                    Function<B, List<T>> itemsOf) {
        List<T> collected = new ArrayList<>();
        String nextUri = uri;
        int page = 0;

        while (nextUri != null && page < properties.maxPages()) {
            ResponseEntity<B> response = execute(nextUri, token, extractor);
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
            log.warn("[GitHub] 페이지네이션 상한({}) 도달. 이후 페이지는 수집 x. uri={}",
                    properties.maxPages(), TokenMasker.mask(uri));
        }

        return collected;
    }

    /** 설치 범위 ∩ 사용자 접근 권한이 이미 적용된 목록이다. 교집합을 따로 계산하지 마라. */
    public List<GithubInstallationResponse> getUserInstallations(String userAccessToken) {
        return getAllPagesWrapped("/user/installations?per_page=100", userAccessToken,
                GithubInstallationsPage.class, GithubInstallationsPage::items);
    }

    public List<GithubRepositoryResponse> getInstallationRepositories(String userAccessToken,
                                                                     Long installationId) {
        return getAllPagesWrapped(
                "/user/installations/" + installationId + "/repositories?per_page=100",
                userAccessToken, GithubRepositoriesPage.class, GithubRepositoriesPage::items);
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
        try {
            return extractor.extract(
                    restClient.get()
                            .uri(uri)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .retrieve()
                            .onStatus(HttpStatusCode::isError, (req, res) -> {
                                throw toException(uri, res.getStatusCode(), res.getHeaders());
                            }));
        } catch (GithubApiException | GithubReauthRequiredException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("[GitHub] 호출 실패 uri={} cause={}",
                    TokenMasker.mask(uri), e.getClass().getSimpleName());
            throw new GithubApiException();
        }
    }

    private RuntimeException toException(String uri, HttpStatusCode status, HttpHeaders headers) {
        RateLimitSnapshot snapshot = RateLimitSnapshot.from(headers);
        boolean secondaryLimit = headers.getFirst(HttpHeaders.RETRY_AFTER) != null;

        if (status.value() == 401) {
            log.info("[GitHub] 401 — user token 만료/무효 uri={}", TokenMasker.mask(uri));
            return new GithubReauthRequiredException();
        }
        if (status.value() == 403 && (snapshot.isExhausted() || secondaryLimit)) {
            log.warn("[GitHub] rate limit uri={} remaining={} resetAt={} retryAfter={}",
                    TokenMasker.mask(uri), snapshot.remaining(), snapshot.resetAt(),
                    headers.getFirst(HttpHeaders.RETRY_AFTER));
            return new GithubApiException(ErrorCode.GITHUB_RATE_LIMITED);
        }
        if (status.value() == 429) {
            return new GithubApiException(ErrorCode.GITHUB_RATE_LIMITED);
        }

        log.warn("[GitHub] 호출 실패 status={} uri={}", status.value(), TokenMasker.mask(uri));
        return new GithubApiException();
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
