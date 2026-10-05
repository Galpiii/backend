package com.github.galpiii.galpi.domain.featurematch.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record FeatureMatchChangesResponse(@Schema(description = "프로젝트 전체 표시 결과와 현재 입력의 일치 여부. 분석 실패만으로 STALE이 되지는 않음") Freshness freshness,
                                          boolean fullRequired,
                                          @Schema(description = "분석이 완료되어 대조 가능한 신규·변경 PR. 다른 PR 분석이나 수집이 진행 중이면 비어 있음")
                                          List<ChangedPullRequest> changedPullRequests,
                                          @Schema(description = "추가·수정된 기능. 이전 기능별 스냅샷이 없으면 목록은 비어도 fullRequired일 수 있음")
                                          List<ChangedFeature> changedFeatures,
                                          List<Long> removedPullRequestIds,
                                          List<Long> removedFeatureIds,
                                          @Schema(description = "현재 대조 가능한 신규·변경 PR과 삭제된 PR의 합계. 분석 중에는 대조 가능한 PR이 제외됨")
                                          int changedPullRequestCount,
                                          @Schema(description = "식별 가능한 변경·추가·삭제 기능의 합계")
                                          int changedFeatureCount,
                                          List<StaleReason> staleReasons,
                                          @Schema(description = "PR 관련 재대조 준비 문제. PR_ANALYSIS_NOT_READY는 수집·분석 진행 또는 결과 미준비, PR_REANALYSIS_REQUIRED는 원본 변경 후 분석 실패·취소를 뜻함. 후자는 다른 준비된 PR의 부분 대조를 막지 않음")
                                          List<RerunBlockReason> rerunBlockReasons) {
    public enum Freshness { CURRENT, STALE }

    public enum StaleReason { PULL_REQUEST_CHANGED, FEATURE_CHANGED }

    public enum RerunBlockReason { PR_ANALYSIS_NOT_READY, PR_REANALYSIS_REQUIRED }

    public record ChangedPullRequest(long pullRequestId, int number, String title) {
    }

    public record ChangedFeature(long featureId, String name) {
    }
}
