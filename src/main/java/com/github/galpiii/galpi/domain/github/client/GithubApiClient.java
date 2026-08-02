package com.github.galpiii.galpi.domain.github.client;

import com.github.galpiii.galpi.domain.github.client.dto.GithubUserResponse;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.config.GithubClientConfig;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.domain.github.exception.GithubReauthRequiredException;
import com.github.galpiii.galpi.global.error.ErrorCode;
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

import java.util.ArrayList;
import java.util.List;

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
                    .orElse(null);
        }

        if (nextUri != null) {
            log.warn("[GitHub] 페이지네이션 상한({}) 도달. 이후 페이지는 수집하지 않는다. uri={}",
                    properties.maxPages(), TokenMasker.mask(uri));
        }

        return collected;
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

    @FunctionalInterface
    private interface ResponseExtractor<T> {
        ResponseEntity<T> extract(RestClient.ResponseSpec spec);
    }
}
