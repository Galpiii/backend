package com.github.galpiii.galpi.domain.analysis.controller;

import com.github.galpiii.galpi.domain.analysis.dto.AnalysisRunCreatedResponse;
import com.github.galpiii.galpi.domain.analysis.dto.AnalysisRunStatusResponse;
import com.github.galpiii.galpi.domain.analysis.service.AnalysisRunService;
import com.github.galpiii.galpi.domain.auth.jwt.AuthPrincipal;
import com.github.galpiii.galpi.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import com.github.galpiii.galpi.domain.analysis.dto.SelectedAnalysisRequest;
import com.github.galpiii.galpi.domain.analysis.dto.RepositoryAnalysisStatusResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "분석 실행")
@RestController
@RequiredArgsConstructor
public class AnalysisRunController {

    private final AnalysisRunService analysisRunService;

    @Operation(summary = "분석 실행 요청",
            description = """
                    프로젝트의 모든 저장소에 대해 현재 사용자의 GitHub 접근 권한을 다시 확인한 뒤
                    작업을 큐에 넣고 즉시 202를 돌려준다. 실제 수집은 워커가 별도로 진행하므로
                    응답을 받았다고 수집이 끝난 것은 아니다.

                    접근 권한을 잃은 저장소는 INACCESSIBLE로 표시되고 이번 분석에서 빠진다.
                    응답의 inaccessibleRepositoryCount가 0이 아니면 사용자에게 알려야 한다.""")
    @PostMapping("/projects/{projectId}/analyses")
    public ResponseEntity<ApiResponse<AnalysisRunCreatedResponse>> create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long projectId) {
        AnalysisRunCreatedResponse created =
                analysisRunService.create(principal.userId(), projectId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(created));
    }

    @Operation(summary = "선택한 저장소만 분석",
            description = "repositoryIds는 현재 프로젝트에 연결된 갈피 내부 저장소 ID다. 다른 저장소는 다시 수집하지 않는다.")
    @PostMapping("/projects/{projectId}/analyses/selected")
    public ResponseEntity<ApiResponse<AnalysisRunCreatedResponse>> createSelected(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long projectId,
            @Valid @RequestBody SelectedAnalysisRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                analysisRunService.create(principal.userId(), projectId, request.repositoryIds())));
    }

    @Operation(summary = "연결된 저장소별 마지막 수집 상태",
            description = "현재 연결된 저장소 전체를 반환한다. 분석 이력이 없으면 status는 NOT_ANALYZED, "
                    + "analysisRunId는 null이다. 종료되거나 요청 제한으로 중단된 작업의 미완료 저장소는 "
                    + "작업 상태를 반영하며, 이미 완료된 저장소의 상태는 유지한다.")
    @GetMapping("/projects/{projectId}/analyses/repositories")
    public ResponseEntity<ApiResponse<List<RepositoryAnalysisStatusResponse>>> repositories(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long projectId) {
        return ResponseEntity.ok(ApiResponse.success(
                analysisRunService.repositoryStatuses(principal.userId(), projectId)));
    }

    @Operation(summary = "분석 진행 상태 조회",
            description = """
                    저장소별 상태와 incompleteReasons를 함께 내린다. PR_LIMIT_EXCEEDED나
                    PATCH_OMITTED처럼 근거가 부족한 이유는 화면에 표시해야 한다.

                    status가 RATE_LIMITED면 rateLimitResumeAt 이후 사용자가 직접 다시 실행한다.
                    서버가 그 시각에 자동으로 재개하지 않는다.""")
    @GetMapping("/analyses/{analysisRunId}")
    public ResponseEntity<ApiResponse<AnalysisRunStatusResponse>> get(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long analysisRunId) {
        return ResponseEntity.ok(ApiResponse.success(
                analysisRunService.get(principal.userId(), analysisRunId)));
    }
}
