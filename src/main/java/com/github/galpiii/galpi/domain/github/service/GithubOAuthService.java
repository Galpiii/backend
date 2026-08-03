package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.auth.config.JwtProperties;
import com.github.galpiii.galpi.domain.auth.store.LoginCodeStore;
import com.github.galpiii.galpi.domain.auth.support.RedirectUriValidator;
import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.client.GithubOAuthClient;
import com.github.galpiii.galpi.domain.github.client.dto.GithubAccessTokenResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubUserResponse;
import com.github.galpiii.galpi.domain.github.dto.AuthorizeRedirect;
import com.github.galpiii.galpi.domain.github.store.OAuthCodeGuard;
import com.github.galpiii.galpi.domain.github.store.OAuthStateStore;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import com.github.galpiii.galpi.global.util.LogSafe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

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

    public AuthorizeRedirect buildAuthorizeRedirect(String returnTo) {
        String validatedReturnTo;
        try {
            validatedReturnTo = redirectUriValidator.validate(returnTo);
        } catch (GlobalException e) {
            return AuthorizeRedirect.withoutState(errorRedirect(e, ""));
        }

        String state = stateStore.issue(validatedReturnTo);
        return new AuthorizeRedirect(oAuthClient.buildAuthorizeUrl(state), state);
    }

    public String handleCallback(String code, String state, String browserState,
                                 String error, String errorDescription) {
        Optional<String> consumedState = consumeStateBoundTo(state, browserState);
        String returnTo = consumedState.orElse("");

        if (error != null && !error.isBlank()) {
            log.info("[GitHub] OAuth 거부 error={} description={}",
                    LogSafe.text(error), LogSafe.text(errorDescription));
            return redirectUriValidator.buildFrontendError(
                    ErrorCode.GITHUB_OAUTH_FAILED.getCode(), returnTo);
        }

        try {
            String loginCode = issueLoginCode(code, consumedState.isPresent());
            return redirectUriValidator.buildFrontendCallback(loginCode, returnTo);
        } catch (GlobalException e) {
            return errorRedirect(e, returnTo);
        } catch (RuntimeException e) {
            log.error("[GitHub] OAuth 콜백 처리 중 예상치 못한 오류", e);
            return redirectUriValidator.buildFrontendError(
                    ErrorCode.INTERNAL_SERVER_ERROR.getCode(), returnTo);
        }
    }

    private Optional<String> consumeStateBoundTo(String state, String browserState) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        if (browserState == null || browserState.isBlank()) {
            log.warn("[GitHub] state 쿠키 없이 콜백이 들어왔다. 이 브라우저가 시작한 흐름이 아니다");
            return Optional.empty();
        }
        if (!constantTimeEquals(state, browserState)) {
            log.warn("[GitHub] 콜백 state가 브라우저 쿠키와 다르다. 로그인 CSRF 시도일 수 있다");
            return Optional.empty();
        }
        return stateStore.consume(state);
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private String issueLoginCode(String code, boolean stateValid) {
        if (code == null || code.isBlank()) {
            throw new BadRequestException(ErrorCode.GITHUB_OAUTH_FAILED);
        }
        if (!stateValid) {
            log.warn("[GitHub] state 검증 실패 (미제공·만료·재사용)");
            throw new BadRequestException(ErrorCode.GITHUB_OAUTH_STATE_INVALID);
        }
        if (!codeGuard.markUsed(code)) {
            log.warn("[GitHub] 이미 사용된 code로 콜백이 재호출됨");
            throw new BadRequestException(ErrorCode.GITHUB_OAUTH_CODE_REUSED);
        }

        GithubAccessTokenResponse tokenResponse = oAuthClient.exchangeCodeForToken(code);
        expiryMonitor.inspect(tokenResponse);

        String userAccessToken = tokenResponse.accessToken();

        GithubUserResponse githubUser = apiClient.getAuthenticatedUser(userAccessToken);
        if (githubUser == null || githubUser.id() == null) {
            log.warn("[GitHub] /user 응답에 식별자가 없음");
            throw new UnauthorizedException(ErrorCode.GITHUB_OAUTH_FAILED);
        }

        User user = githubUserService.upsert(githubUser);
        userTokenService.save(user, userAccessToken, tokenResponse.expiresIn());

        return loginCodeStore.issue(user.getId(), jwtProperties.loginCodeTtl());
    }

    private String errorRedirect(GlobalException e, String returnTo) {
        log.info("[GitHub] OAuth 흐름 실패. 프론트 오류 화면으로 넘긴다 code={}", e.getErrorCode().getCode());
        return redirectUriValidator.buildFrontendError(e.getErrorCode().getCode(), returnTo);
    }
}
