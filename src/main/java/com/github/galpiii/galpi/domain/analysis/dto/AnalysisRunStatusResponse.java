package com.github.galpiii.galpi.domain.analysis.dto;

import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRun;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunStatus;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunTarget;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunTargetStatus;
import com.github.galpiii.galpi.domain.collection.entity.DataCompleteness;
import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 진행 상태 폴링 응답.
 *
 * <p>저장소별 {@code incompleteReasons}를 그대로 내린다. {@code PR_LIMIT_EXCEEDED}처럼
 * "근거가 왜 부족한지"를 설명하는 값이 화면에 보여야 하기 때문이다 — 300개를 넘는 저장소에서는
 * 초기 구현 기능이 PR 근거 없이 코드로만 잡히는데, 이 사실이 안 보이면 사용자는 결과를
 * 오해한다.
 */
@Schema(description = "분석 작업 진행 상태")
public record AnalysisRunStatusResponse(
        @Schema(description = "분석 작업 id") Long analysisRunId,
        @Schema(description = "작업 전체 상태") AnalysisRunStatus status,
        @Schema(description = "rate limit으로 중단된 경우 재시도 가능한 시각. 자동 재개는 하지 않는다")
        OffsetDateTime rateLimitResumeAt,
        @Schema(description = "실패 코드") String errorCode,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        @Schema(description = "저장소별 결과") List<RepositoryStatus> repositories
) {

    public static AnalysisRunStatusResponse of(AnalysisRun run, List<AnalysisRunTarget> targets) {
        return new AnalysisRunStatusResponse(
                run.getId(),
                run.getStatus(),
                run.getRateLimitResumeAt(),
                run.getErrorCode(),
                run.getStartedAt(),
                run.getFinishedAt(),
                targets.stream().map(RepositoryStatus::from).toList());
    }

    @Schema(description = "저장소 하나의 수집 결과")
    public record RepositoryStatus(
            @Schema(description = "갈피 내부 저장소 id") Long repositoryId,
            String fullName,
            AnalysisRunTargetStatus status,
            @Schema(description = "분석 기준 커밋") String analyzedCommitSha,
            int collectedFileCount,
            long collectedBytes,
            int excludedFileCount,
            int prCollectedCount,
            DataCompleteness dataCompleteness,
            @Schema(description = "수집이 온전하지 못한 이유. 화면에 표시해야 한다")
            List<IncompleteReason> incompleteReasons,
            String errorCode
    ) {

        public static RepositoryStatus from(AnalysisRunTarget target) {
            List<IncompleteReason> reasons = target.getIncompleteReasons();
            return new RepositoryStatus(
                    target.getRepository().getId(),
                    target.getRepository().getFullName(),
                    target.getStatus(),
                    target.getAnalyzedCommitSha(),
                    target.getCollectedFileCount(),
                    target.getCollectedBytes(),
                    target.getExcludedFileCount(),
                    target.getPrCollectedCount(),
                    target.getDataCompleteness(),
                    reasons == null ? List.of() : reasons,
                    target.getErrorCode());
        }
    }
}
