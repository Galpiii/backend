package com.github.galpiii.galpi.domain.github.client;

import com.github.galpiii.galpi.domain.github.appjwt.GithubAppJwtGenerator;
import com.github.galpiii.galpi.domain.github.client.dto.GithubInstallationAccessTokenResponse;
import com.github.galpiii.galpi.domain.github.config.GithubClientConfig;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.domain.github.exception.GithubInstallationUnavailableException;
import com.github.galpiii.galpi.domain.github.exception.GithubRateLimitedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Installation access token 발급.
 *
 * <p>두 가지를 반드시 지킨다.
 *
 * <ul>
 *   <li><b>{@code repository_ids}로 범위를 좁힌다.</b> 설치 범위 전체에 유효한 토큰을 만들면,
 *       이번 분석과 무관한 저장소까지 읽을 수 있는 자격증명이 한 시간 동안 떠 있게 된다.</li>
 *   <li><b>{@code permissions}를 요청 단위로 줄인다.</b> Phase 1은 CI를 수집하지 않으므로
 *       {@code actions}를 넣지 않는다. App에 권한이 있어도 토큰에는 담지 않는다.</li>
 * </ul>
 *
 * <p>토큰 값은 로그·예외 어디에도 남기지 않는다. 발급 실패 로그에 응답 본문을 넣지 않는 것도
 * 같은 이유다 — 실패 본문에는 토큰이 없지만, 성공 경로와 코드를 공유하다 실수하기 쉽다.
 */
@Slf4j
@Component
public class GithubInstallationTokenClient {

    /** Phase 1이 읽는 것: 저장소 메타데이터, 파일 내용, PR. 그 이상은 받지 않는다. */
    private static final Map<String, String> REQUESTED_PERMISSIONS = Map.of(
            "metadata", "read",
            "contents", "read",
            "pull_requests", "read");

    private final RestClient restClient;
    private final GithubAppJwtGenerator appJwtGenerator;

    public GithubInstallationTokenClient(
            @Qualifier(GithubClientConfig.API_CLIENT) RestClient restClient,
            GithubAppJwtGenerator appJwtGenerator) {
        this.restClient = restClient;
        this.appJwtGenerator = appJwtGenerator;
    }

    /**
     * @param repositoryIds 이번 작업에 필요한 GitHub 저장소 id. 비어 있으면 안 된다
     */
    public GithubInstallationAccessTokenResponse issue(Long installationId,
                                                       List<Long> repositoryIds) {
        if (repositoryIds == null || repositoryIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "installation token은 저장소 범위를 지정해서만 발급한다");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repository_ids", repositoryIds);
        body.put("permissions", REQUESTED_PERMISSIONS);

        try {
            GithubInstallationAccessTokenResponse response = restClient.post()
                    .uri("/app/installations/{installationId}/access_tokens", installationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + appJwtGenerator.generate())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, res) -> {
                        String responseBody = new String(res.getBody().readNBytes(8_192),
                                StandardCharsets.UTF_8);
                        throw toException(installationId, res.getStatusCode(), res.getHeaders(),
                                responseBody);
                    })
                    .body(GithubInstallationAccessTokenResponse.class);

            if (response == null || response.token() == null || response.token().isBlank()) {
                log.warn("[GitHub] installation token 응답에 토큰이 없다 installationId={}",
                        installationId);
                throw new GithubApiException();
            }
            return response;
        } catch (GithubApiException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("[GitHub] installation token 발급 호출 실패 installationId={} cause={}",
                    installationId, e.getClass().getSimpleName());
            throw new GithubApiException();
        }
    }

    /**
     * 401과 404는 모두 "이 설치로는 더 이상 아무것도 못 한다"는 뜻이다.
     *
     * <p>401은 App JWT가 거부된 경우(설치가 사라졌거나 App 자격증명이 바뀜), 404는 설치 자체가
     * 없는 경우다. 둘 다 재시도로 풀리지 않으므로 재연결을 유도한다. 403은 rate limit과 정지된
     * 설치를 갈라야 한다 — 앞의 것은 잠시 후 되고 뒤의 것은 안 된다.
     */
    private RuntimeException toException(Long installationId, HttpStatusCode status,
                                         HttpHeaders headers, String body) {
        int code = status.value();
        if (code == 401 || code == 404) {
            log.info("[GitHub] installation token 발급 거부 status={} installationId={}",
                    code, installationId);
            return new GithubInstallationUnavailableException();
        }
        if (code == 403 && GithubRateLimits.isRateLimited(headers, body)) {
            return new GithubRateLimitedException(GithubRateLimits.retryAfterSeconds(
                    headers, RateLimitSnapshot.from(headers)));
        }
        if (code == 403 || code == 422) {
            // 정지된 설치이거나 요청한 저장소가 설치 범위 밖이다. 재시도로 풀리지 않는다.
            log.warn("[GitHub] installation token 발급 실패 status={} installationId={}",
                    code, installationId);
            return new GithubInstallationUnavailableException();
        }
        if (code == 429) {
            return new GithubRateLimitedException(GithubRateLimits.retryAfterSeconds(
                    headers, RateLimitSnapshot.from(headers)));
        }

        log.warn("[GitHub] installation token 발급 실패 status={} installationId={}",
                code, installationId);
        return new GithubApiException();
    }
}
