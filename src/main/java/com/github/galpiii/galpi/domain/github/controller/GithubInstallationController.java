package com.github.galpiii.galpi.domain.github.controller;

import com.github.galpiii.galpi.domain.auth.jwt.AuthPrincipal;
import com.github.galpiii.galpi.domain.github.dto.InstallUrlResponse;
import com.github.galpiii.galpi.domain.github.dto.InstallationRepositoriesResponse;
import com.github.galpiii.galpi.domain.github.dto.InstallationSummaryResponse;
import com.github.galpiii.galpi.domain.github.service.GithubInstallationService;
import com.github.galpiii.galpi.domain.github.service.GithubSetupService;
import com.github.galpiii.galpi.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "GitHub 저장소 선택")
@RestController
@RequiredArgsConstructor
@RequestMapping("/github")
public class GithubInstallationController {

    private final GithubSetupService githubSetupService;
    private final GithubInstallationService githubInstallationService;

    @Operation(summary = "App 설치 URL 발급",
            description = """
                    설치 흐름을 시작한 사실을 state 키와 사용자 키에 15분간 함께 기록하고
                    설치 페이지 URL을 돌려준다. returnTo는 허용 목록 안이어야 한다.""")
    @GetMapping("/install-url")
    public ResponseEntity<ApiResponse<InstallUrlResponse>> installUrl(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(name = "returnTo", required = false) String returnTo) {
        return ResponseEntity.ok(ApiResponse.success(
                githubSetupService.buildInstallUrl(principal.userId(), returnTo)));
    }

    @Operation(summary = "설치 목록 조회",
            description = """
                    사용자가 접근할 수 있는 installation만 돌려준다. 목록이 비어 있으면
                    App 미설치, repositorySelection이 selected인데 저장소가 없으면
                    '설치는 됐지만 고른 저장소가 없음'이다.""")
    @GetMapping("/installations")
    public ResponseEntity<ApiResponse<List<InstallationSummaryResponse>>> installations(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                githubInstallationService.listInstallations(principal.userId())));
    }

    @Operation(summary = "선택 가능한 저장소 조회",
            description = """
                    installation별로 묶어 돌려준다. 이 목록은 DB에 저장하지 않는다.
                    projectId를 주면 이미 연결된 저장소에 linked 표시가 붙고, 그 저장소의
                    이름·소유자 스냅샷이 현재 값으로 갱신된다.""")
    @GetMapping("/repositories")
    public ResponseEntity<ApiResponse<List<InstallationRepositoriesResponse>>> repositories(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(name = "projectId", required = false) Long projectId) {
        return ResponseEntity.ok(ApiResponse.success(
                githubInstallationService.listRepositories(principal.userId(), projectId)));
    }
}
