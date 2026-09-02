package com.github.galpiii.galpi.domain.pullrequest.controller;

import com.github.galpiii.galpi.domain.auth.jwt.AuthPrincipal;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestAnalysisRetryResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestDetailResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestListResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestOverviewResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestSort;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.domain.pullrequest.service.PullRequestAnalysisRetryService;
import com.github.galpiii.galpi.domain.pullrequest.service.PullRequestQueryService;
import com.github.galpiii.galpi.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PR 조회 API.
 *
 * <p>클래스 단위 {@code @RequestMapping}을 두지 않는다. 목록·집계·재시도는 프로젝트 아래에
 * 있고 상세는 {@code /pull-requests/{id}}라 공통 접두사가 없다. 상세가 프로젝트 아래에 있지
 * 않은 이유는 {@code PullRequestApi}에 적어 뒀다.
 *
 * <p>경로에 {@code /api} 접두사를 붙이지 않는다. 기존 컨트롤러가 전부
 * {@code /projects}·{@code /analyses}·{@code /feature-specs}를 쓴다.
 */
@RestController
@RequiredArgsConstructor
public class PullRequestController implements PullRequestApi {

    private final PullRequestQueryService queryService;
    private final PullRequestAnalysisRetryService retryService;

    @Override
    @GetMapping("/projects/{projectId}/pull-requests")
    public ResponseEntity<ApiResponse<PullRequestListResponse>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long projectId,
            @RequestParam(name = "repositoryId", required = false) Long repositoryId,
            @RequestParam(name = "authorLogin", required = false) String authorLogin,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "analysisStatus", required = false)
            PullRequestAnalysisStatus analysisStatus,
            @RequestParam(name = "sort", required = false) PullRequestSort sort,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        return ResponseEntity.ok(ApiResponse.success(queryService.list(
                principal.userId(), projectId, repositoryId, authorLogin, query,
                analysisStatus, sort, page, size)));
    }

    @Override
    @GetMapping("/projects/{projectId}/pull-requests/summary")
    public ResponseEntity<ApiResponse<PullRequestOverviewResponse>> overview(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long projectId) {
        return ResponseEntity.ok(ApiResponse.success(
                queryService.overview(principal.userId(), projectId)));
    }

    @Override
    @GetMapping("/pull-requests/{pullRequestId}")
    public ResponseEntity<ApiResponse<PullRequestDetailResponse>> detail(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long pullRequestId) {
        return ResponseEntity.ok(ApiResponse.success(
                queryService.detail(principal.userId(), pullRequestId)));
    }

    @Override
    @PostMapping("/projects/{projectId}/pull-request-analyses/retry")
    public ResponseEntity<ApiResponse<PullRequestAnalysisRetryResponse>> retry(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long projectId,
            @RequestParam(name = "repositoryId", required = false) Long repositoryId) {
        // 202다. 큐에 넣기만 하고 워커가 나중에 처리한다 -- 응답이 돌아온 시점에 요약이
        // 끝나 있지 않다는 사실을 상태 코드로 알린다.
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(
                        retryService.retry(principal.userId(), projectId, repositoryId)));
    }
}
