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
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    /**
     * 브라우저를 보낼 URL을 만든다. returnTo가 허용 목록 밖이면 예외 대신 프론트 오류 화면 URL을
     * 준다. 이 엔드포인트는 사용자가 직접 이동하는 곳이라, 실패해도 브라우저에 JSON을 띄우면 안 된다.
     */
    public String buildAuthorizeRedirect(String returnTo) {
        String validatedReturnTo;
        try {
            validatedReturnTo = redirectUriValidator.validate(returnTo);
        } catch (GlobalException e) {
            // 거부된 returnTo는 오류 화면에도 싣지 않는다.
            return errorRedirect(e, "");
        }

        String state = stateStore.issue(validatedReturnTo);
        return oAuthClient.buildAuthorizeUrl(state);
    }

    /**
     * 콜백은 GitHub이 브라우저를 되돌려 보내는 지점이다. 어떤 실패든 프론트 오류 화면으로 넘겨야
     * 하므로 예외를 밖으로 던지지 않는다. 던지면 사용자가 백엔드 도메인의 JSON 응답에 착륙한다.
     */
    public String handleCallback(String code, String state, String error, String errorDescription) {
        // 성공·실패와 무관하게 state는 먼저 소진한다. 복귀 경로는 여기서만 알 수 있다.
        Optional<String> consumedState = stateStore.consume(state);
        String returnTo = consumedState.orElse("");

        if (error != null && !error.isBlank()) {
            log.info("[GitHub] OAuth 거부 error={} description={}", error, errorDescription);
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
