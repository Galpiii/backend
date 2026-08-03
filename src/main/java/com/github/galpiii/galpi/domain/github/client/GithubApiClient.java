package com.github.galpiii.galpi.domain.github.client;

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
import java.util.Map;

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
        List<T> collected = new ArrayList<>();
        String nextUri = uri;
        int page = 0;

        while (nextUri != null && page < properties.maxPages()) {
            ResponseEntity<List<T>> response =
                    execute(nextUri, token, spec -> spec.toEntity(pageType));
            List<T> body = response.getBody();

            if (body != null) {
                collected.addAll(body);
            }
            page++;
            nextUri = LinkHeaderParser.next(response.getHeaders().getFirst(HttpHeaders.LINK))
                    .filter(this::withinApiOrigin)
                    .orElse(null);
        }

        if (nextUri != null) {
            log.warn("[GitHub] 페이지네이션 상한({}) 도달. 이후 페이지는 수집 x. uri={}",
                    properties.maxPages(), TokenMasker.mask(uri));
        }

        return collected;
    }

    /**
     * Link 헤더가 가리키는 다음 페이지가 API 오리진 안에 있는지 확인한다.
     *
     * <p>다음 요청에는 사용자 access token이 Bearer로 붙는다. 절대 URL을 검증 없이 따라가면
     * 응답 헤더 하나로 자격증명을 임의의 호스트에 흘릴 수 있다. RestClient는 절대 URL이면
     * baseUrl을 무시하고, followRedirects(NEVER)도 이 경로는 막지 못하므로 여기서 강제한다.
     */
    private boolean withinApiOrigin(String nextUri) {
        URI next;
        URI apiBase;
        try {
            next = new URI(nextUri);
            apiBase = new URI(properties.apiBaseUrl());
        } catch (URISyntaxException e) {
            log.warn("[GitHub] Link 헤더를 URI로 읽을 수 없어 페이지네이션을 멈춘다");
            return false;
        }

        if (!next.isAbsolute()) {
            return true;
        }

        boolean sameOrigin = next.getScheme().equalsIgnoreCase(apiBase.getScheme())
                && next.getHost() != null
                && next.getHost().equalsIgnoreCase(apiBase.getHost())
                && next.getPort() == apiBase.getPort();

        if (!sameOrigin) {
            log.warn("[GitHub] Link 헤더가 API 오리진 밖을 가리켜 페이지네이션을 멈춘다 host={}",
                    LogSafe.text(next.getHost()));
        }
        return sameOrigin;
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
