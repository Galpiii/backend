package com.github.galpiii.galpi.domain.github.controller;

import com.github.galpiii.galpi.domain.auth.jwt.AuthPrincipal;
import com.github.galpiii.galpi.domain.github.dto.InstallUrlResponse;
import com.github.galpiii.galpi.domain.github.dto.InstallationSummaryResponse;
import com.github.galpiii.galpi.domain.github.dto.SelectableRepositoriesResponse;
import com.github.galpiii.galpi.domain.github.service.GithubInstallationService;
import com.github.galpiii.galpi.domain.github.service.GithubSetupService;
import com.github.galpiii.galpi.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
                    설치 흐름을 시작한 사실을 일회성 state 키에 15분간 기록하고
                    설치 페이지 URL을 돌려준다. returnTo는 허용 목록 안이어야 한다.

                    저장소를 고른 상태로 설치 화면에 나가면 프론트 상태가 날아가므로,
                    selectedRepositoryIds를 함께 보내면 서버가 들고 있다가 setup 콜백의
                    리다이렉트에 실어 돌려준다. 그 사이 접근할 수 없게 된 저장소는 빠지고
                    unavailableRepositoryIds로 따로 나간다.""")
    @PostMapping("/install-url")
    public ResponseEntity<ApiResponse<InstallUrlResponse>> installUrl(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(name = "returnTo", required = false) String returnTo,
            @RequestParam(name = "selectedRepositoryIds", required = false)
            List<Long> selectedRepositoryIds) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(githubSetupService.buildInstallUrl(
                        principal.userId(), returnTo, selectedRepositoryIds)));
    }

    @Operation(summary = "설치 목록 조회",
            description = """
                    사용자가 접근할 수 있는 installation만 돌려준다. 목록이 비어 있으면
                    App 미설치, repositorySelection이 selected인데 저장소가 없으면
                    '설치는 됐지만 고른 저장소가 없음'이다. suspended가 true면 GitHub에서
                    정지된 설치이므로 저장소 API를 호출하지 않는다.""")
    @GetMapping("/installations")
    public ResponseEntity<ApiResponse<List<InstallationSummaryResponse>>> installations(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(
                        githubInstallationService.listInstallations(principal.userId())));
    }

    @Operation(summary = "선택 가능한 저장소 조회",
            description = """
                    installation별로 묶어 돌려준다. 이 목록은 DB에 저장하지 않는다.
                    projectId를 주면 이미 연결된 저장소에 linked 표시가 붙는다.

                    읽지 못한 installation은 failedInstallations에 사유(SUSPENDED / NOT_FOUND /
                    FORBIDDEN)와 함께 따로 나간다. 설치 하나가 죽어도 나머지 저장소는 그대로
                    표시된다 — 화면의 "조직 권한 오류 보기"가 이 배열을 쓴다.

                    검색·필터·정렬은 서버가 하지 않는다. 목록이 사용자당 수십 개 규모라 전체를
                    한 번에 주고 클라이언트가 걸러 쓴다.""")
    @GetMapping("/repositories")
    public ResponseEntity<ApiResponse<SelectableRepositoriesResponse>> repositories(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(name = "projectId", required = false) Long projectId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(
                        githubInstallationService.listRepositories(principal.userId(), projectId)));
    }
}
