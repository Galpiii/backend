package com.github.galpiii.galpi.domain.github.controller;

import com.github.galpiii.galpi.domain.auth.jwt.AuthPrincipal;
import com.github.galpiii.galpi.domain.github.service.GithubConnectionService;
import com.github.galpiii.galpi.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "GitHub 연결")
@RestController
@RequiredArgsConstructor
@RequestMapping("/auth/github")
public class GithubConnectionController {

    private final GithubConnectionService githubConnectionService;

    @Operation(summary = "GitHub 연결 해제",
            description = """
                    저장된 GitHub user token을 폐기하고 연결 상태를 DISCONNECTED로 바꾼다.
                    갈피 세션은 유지되므로 로그인 상태는 그대로이고, /auth/me의
                    github.githubTokenValid만 false가 된다.
                    다시 쓰려면 /auth/github/authorize로 재연결한다.""")
    @DeleteMapping("/connection")
    public ResponseEntity<ApiResponse<Void>> disconnect(
            @AuthenticationPrincipal AuthPrincipal principal) {
        githubConnectionService.disconnect(principal.userId());
        return ResponseEntity.ok(ApiResponse.success());
    }
}
