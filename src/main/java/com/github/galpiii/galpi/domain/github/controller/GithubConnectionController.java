package com.github.galpiii.galpi.domain.github.controller;

import com.github.galpiii.galpi.domain.auth.jwt.AuthPrincipal;
import com.github.galpiii.galpi.domain.github.dto.GithubDisconnectResponse;
import com.github.galpiii.galpi.domain.github.dto.RepositoryAccessResyncResponse;
import com.github.galpiii.galpi.domain.github.service.GithubConnectionService;
import com.github.galpiii.galpi.domain.github.service.GithubRepositoryAccessService;
import com.github.galpiii.galpi.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "GitHub 연결")
@RestController
@RequiredArgsConstructor
@RequestMapping("/auth/github")
public class GithubConnectionController {

    private final GithubConnectionService githubConnectionService;
    private final GithubRepositoryAccessService repositoryAccessService;

    @Operation(summary = "GitHub 연결 해제",
            description = """
                    GitHub App authorization 전체를 폐기하고 연결 상태를 DISCONNECTED로 바꾼다.
                    확인 다이얼로그에 "갈피 앱에 준 권한 전체가 해제된다"는 의미를 명시해야 한다.

                    갈피 세션은 유지되므로 로그인 상태는 그대로이고, /auth/me의
                    github.githubTokenValid만 false가 된다. 저장소 연결·PR·분석 결과는 지우지
                    않으며, 진행 중이던 분석만 CANCELLED가 된다.

                    저장된 토큰이 만료됐으면 폐기 API를 부를 수 없다. 그래도 재인증을 요구하지
                    않고 authorizationRevoked=false로 응답하므로, 그때는 authorizationsUrl을
                    안내한다. App 설치는 자동으로 지우지 않는다(installationsUrl).

                    다시 쓰려면 /auth/github/authorize로 재연결한다.""")
    @DeleteMapping("/connection")
    public ResponseEntity<ApiResponse<GithubDisconnectResponse>> disconnect(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                githubConnectionService.disconnect(principal.userId())));
    }

    @Operation(summary = "저장소 접근 상태 재확인",
            description = """
                    연결해 둔 저장소가 지금도 보이는지 GitHub에 다시 물어 access_status를 맞춘다.
                    재연결 직후에 부른다 — 연결을 끊어도 저장소 행은 남기 때문에 대부분 그대로
                    복구되지만, 그 사이 조직에서 나갔거나 설치 범위에서 빠진 저장소는 다시
                    보이지 않는다.

                    보이지 않는 저장소는 지우지 않고 INACCESSIBLE로 두므로, 프로젝트 화면은
                    해당 저장소에만 경고 배지를 띄우고 나머지는 정상 표시한다.""")
    @PostMapping("/connection/repositories/resync")
    public ResponseEntity<ApiResponse<RepositoryAccessResyncResponse>> resyncRepositoryAccess(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                repositoryAccessService.resync(principal.userId())));
    }
}
