package com.github.galpiii.galpi.domain.analysis.dto;

import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 작업 생성 응답.
 *
 * @param inaccessibleRepositoryCount 접근 권한을 잃어 이번 분석에서 빠진 저장소 수.
 *                                    0이 아니면 화면이 그 사실을 알려야 한다
 */
@Schema(description = "분석 작업 생성 결과")
public record AnalysisRunCreatedResponse(
        @Schema(description = "생성된 분석 작업 id") Long analysisRunId,
        @Schema(description = "생성 직후 상태. 항상 QUEUED") AnalysisRunStatus status,
        @Schema(description = "이번 분석이 처리할 저장소 수") int repositoryCount,
        @Schema(description = "접근 권한이 없어 제외된 저장소 수") int inaccessibleRepositoryCount
) {
}
