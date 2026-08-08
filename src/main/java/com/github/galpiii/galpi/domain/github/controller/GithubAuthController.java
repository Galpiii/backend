package com.github.galpiii.galpi.domain.github.controller;

import com.github.galpiii.galpi.domain.github.dto.AuthorizeRedirect;
import com.github.galpiii.galpi.domain.github.service.GithubOAuthService;
import com.github.galpiii.galpi.domain.github.support.OAuthStateCookieFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Tag(name = "GitHub 로그인")
@RestController
@RequiredArgsConstructor
@SecurityRequirements
@RequestMapping("/auth/github")
public class GithubAuthController {

    private final GithubOAuthService githubOAuthService;
    private final OAuthStateCookieFactory stateCookieFactory;

    @Operation(summary = "GitHub 인증 시작",
            description = """
                    state를 발급해 Redis에 5분간 보관하고, 같은 값을 HttpOnly 쿠키로 심어
                    이 브라우저에 묶은 뒤 GitHub 인증 페이지로 리다이렉트한다.
                    returnTo가 허용 목록 밖이면 프론트 오류 화면으로 리다이렉트한다.""")
    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(
            @RequestParam(name = "returnTo", required = false) String returnTo) {
        AuthorizeRedirect redirect = githubOAuthService.buildAuthorizeRedirect(returnTo);
        ResponseCookie stateCookie = redirect.hasState()
                ? stateCookieFactory.create(redirect.state())
                : stateCookieFactory.expired();

        return ResponseEntity.status(302)
                .location(URI.create(redirect.url()))
                .header(HttpHeaders.SET_COOKIE, stateCookie.toString())
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @Operation(summary = "GitHub 인증 콜백",
            description = """
                    쿼리 state가 authorize 때 심어 둔 쿠키와 일치하는지 확인하고,
                    code를 user access token으로 교환한 뒤 일회용 로그인 코드만 실어
                    프론트로 리다이렉트한다. Access JWT는 쿼리스트링에 담지 않는다.
                    실패해도 JSON 대신 프론트 오류 화면(?error=코드)으로 리다이렉트한다.""")
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "error", required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription,
            @CookieValue(name = OAuthStateCookieFactory.COOKIE_NAME, required = false)
            String browserState) {
        String redirectUrl =
                githubOAuthService.handleCallback(code, state, browserState, error, errorDescription);
        return ResponseEntity.status(302)
                .location(URI.create(redirectUrl))
                .header(HttpHeaders.SET_COOKIE, stateCookieFactory.expired().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .build();
    }
}
