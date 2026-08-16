package com.github.galpiii.galpi.domain.github.controller;

import com.github.galpiii.galpi.domain.github.service.GithubSetupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * GitHub이 브라우저를 직접 보내는 설치 콜백.
 *
 * <p>1A의 OAuth 콜백과 같은 성격이라 {@code /auth/github} 아래에 둔다. 이 경로는 App 설정의
 * Setup URL에 그대로 등록되어 있으므로, 옮기려면 GitHub App 설정을 함께 바꿔야 한다.
 */
@Tag(name = "GitHub 설치")
@RestController
@RequiredArgsConstructor
@SecurityRequirements
@RequestMapping("/auth/github")
public class GithubSetupController {

    private final GithubSetupService githubSetupService;

    @Operation(summary = "GitHub App 설치 콜백",
            description = """
                    설치를 시작한 사용자를 state로 확인한다. state가 없으면 거부한다.
                    installation_id는 사용자 토큰으로 조회한 설치 목록과 대조해 위조를 막는다.
                    확인되지 않아도 오류가 아니라 '설치 확인 안 됨' 상태로 프론트에 넘긴다.""")
    @GetMapping("/setup/callback")
    public ResponseEntity<Void> setupCallback(
            @RequestParam(name = "installation_id", required = false) Long installationId,
            @RequestParam(name = "setup_action", required = false) String setupAction,
            @RequestParam(name = "state", required = false) String state) {
        String redirectUrl =
                githubSetupService.handleSetupCallback(installationId, setupAction, state);

        return ResponseEntity.status(302)
                .location(URI.create(redirectUrl))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .build();
    }
}
