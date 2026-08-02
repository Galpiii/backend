package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.auth.config.JwtProperties;
import com.github.galpiii.galpi.domain.auth.store.LoginCodeStore;
import com.github.galpiii.galpi.domain.auth.support.RedirectUriValidator;
import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.client.GithubOAuthClient;
import com.github.galpiii.galpi.domain.github.client.dto.GithubAccessTokenResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubUserResponse;
import com.github.galpiii.galpi.domain.github.store.OAuthCodeGuard;
import com.github.galpiii.galpi.domain.github.store.OAuthStateStore;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GithubOAuthService {

    private final GithubOAuthClient oAuthClient;
    private final GithubApiClient apiClient;
    private final GithubUserService githubUserService;
    private final GithubUserTokenService userTokenService;
    private final GithubTokenExpiryMonitor expiryMonitor;
    private final OAuthStateStore stateStore;
    private final OAuthCodeGuard codeGuard;
    private final LoginCodeStore loginCodeStore;
    private final RedirectUriValidator redirectUriValidator;
    private final JwtProperties jwtProperties;

    public String buildAuthorizeUrl(String returnTo) {
        String validatedReturnTo = redirectUriValidator.validate(returnTo);
        String state = stateStore.issue(validatedReturnTo);
        return oAuthClient.buildAuthorizeUrl(state);
    }

    public String handleCallback(String code, String state, String error, String errorDescription) {
        if (error != null && !error.isBlank()) {
            log.info("[GitHub] OAuth 거부 error={} description={}", error, errorDescription);
            String returnTo = stateStore.consume(state).orElse("");
            return redirectUriValidator.buildFrontendError(
                    ErrorCode.GITHUB_OAUTH_FAILED.getCode(), returnTo);
        }
        if (code == null || code.isBlank()) {
            throw new BadRequestException(ErrorCode.GITHUB_OAUTH_FAILED);
        }

        String returnTo = stateStore.consume(state)
                .orElseThrow(() -> {
                    log.warn("[GitHub] state 검증 실패 (미제공·만료·재사용)");
                    return new BadRequestException(ErrorCode.GITHUB_OAUTH_STATE_INVALID);
                });

        if (!codeGuard.markUsed(code)) {
            log.warn("[GitHub] 이미 사용된 code로 콜백이 재호출되었다");
            throw new BadRequestException(ErrorCode.GITHUB_OAUTH_CODE_REUSED);
        }

        GithubAccessTokenResponse tokenResponse = oAuthClient.exchangeCodeForToken(code);
        expiryMonitor.inspect(tokenResponse);

        String userAccessToken = tokenResponse.accessToken();

        GithubUserResponse githubUser = apiClient.getAuthenticatedUser(userAccessToken);
        if (githubUser == null || githubUser.id() == null) {
            log.warn("[GitHub] /user 응답에 식별자가 없다");
            throw new UnauthorizedException(ErrorCode.GITHUB_OAUTH_FAILED);
        }

        User user = githubUserService.upsert(githubUser);
        userTokenService.save(user, userAccessToken, tokenResponse.expiresIn());

        String loginCode = loginCodeStore.issue(user.getId(), jwtProperties.loginCodeTtl());
        return redirectUriValidator.buildFrontendCallback(loginCode, returnTo);
    }
}
