package com.github.galpiii.galpi.domain.auth.controller;

import com.github.galpiii.galpi.domain.auth.dto.AccessTokenResponse;
import com.github.galpiii.galpi.domain.auth.dto.IssuedTokens;
import com.github.galpiii.galpi.domain.auth.dto.LoginCodeExchangeRequest;
import com.github.galpiii.galpi.domain.auth.dto.MeResponse;
import com.github.galpiii.galpi.domain.auth.jwt.AuthPrincipal;
import com.github.galpiii.galpi.domain.auth.service.AuthService;
import com.github.galpiii.galpi.domain.auth.support.RefreshCookieFactory;
import com.github.galpiii.galpi.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증")
@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshCookieFactory refreshCookieFactory;

    @Operation(summary = "로그인 코드 교환",
            description = "GitHub 콜백에서 받은 일회용 코드를 Access JWT와 Refresh 쿠키로 교환한다.")
    @SecurityRequirements
    @PostMapping("/token")
    public ResponseEntity<ApiResponse<AccessTokenResponse>> exchange(
            @Valid @RequestBody LoginCodeExchangeRequest request) {
        return withRefreshCookie(authService.exchangeLoginCode(request.code()));
    }

    @Operation(summary = "Access 토큰 재발급",
            description = "Refresh 쿠키를 회전시키고 새 Access JWT를 발급한다. 사용된 Refresh는 즉시 폐기된다.")
    @SecurityRequirements
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AccessTokenResponse>> refresh(
            @CookieValue(name = "${galpi.jwt.cookie.name:galpi_refresh}", required = false)
            String refreshToken) {
        return withRefreshCookie(authService.refresh(refreshToken));
    }

    @Operation(summary = "로그아웃",
            description = "Refresh 토큰과 GitHub user token을 폐기하고 쿠키를 만료시킨다.")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = "${galpi.jwt.cookie.name:galpi_refresh}", required = false)
            String refreshToken,
            @AuthenticationPrincipal AuthPrincipal principal) {
        authService.logout(refreshToken, principal == null ? null : principal.userId());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookieFactory.expired().toString())
                .body(ApiResponse.success());
    }

    @Operation(summary = "내 정보",
            description = """
                    GitHub user token이 만료돼도 정상 동작한다.
                    github.githubTokenValid가 false면 프론트는 GitHub 재연결 배너를 띄운다.""")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MeResponse>> me(@AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(authService.getMe(principal.userId())));
    }

    private ResponseEntity<ApiResponse<AccessTokenResponse>> withRefreshCookie(IssuedTokens tokens) {
        ResponseCookie cookie = refreshCookieFactory.create(tokens.refreshToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.success(AccessTokenResponse.of(tokens)));
    }
}
