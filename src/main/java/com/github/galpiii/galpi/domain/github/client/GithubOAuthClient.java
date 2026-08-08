package com.github.galpiii.galpi.domain.github.client;

import com.github.galpiii.galpi.domain.github.client.dto.GithubAccessTokenResponse;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.config.GithubClientConfig;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import com.github.galpiii.galpi.global.util.LogSafe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
public class GithubOAuthClient {

    private final RestClient restClient;
    private final GithubAppProperties properties;

    public GithubOAuthClient(@Qualifier(GithubClientConfig.OAUTH_CLIENT) RestClient restClient,
                             GithubAppProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString(properties.authorizeUrl())
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.oauthCallbackUrl())
                .queryParam("state", state)
                .build(true)
                .toUriString();
    }

    public GithubAccessTokenResponse exchangeCodeForToken(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("code", code);
        form.add("redirect_uri", properties.oauthCallbackUrl());

        GithubAccessTokenResponse response;

        try {
            response = restClient.post()
                    .uri("/login/oauth/access_token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(GithubAccessTokenResponse.class);
        } catch (RestClientException e) {
            log.warn("[GitHub] access_token 교환 실패: {}", e.getClass().getSimpleName());
            throw new UnauthorizedException(ErrorCode.GITHUB_OAUTH_FAILED);
        }

        if (response == null || response.isError()) {
            log.warn("[GitHub] access_token 응답 오류 error={} description={}",
                    response == null ? "null" : LogSafe.text(response.error()),
                    response == null ? "null" : LogSafe.text(response.errorDescription()));
            throw new UnauthorizedException(ErrorCode.GITHUB_OAUTH_FAILED);
        }

        return response;
    }
}
