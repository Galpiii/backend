package com.github.galpiii.galpi.domain.featurematch.dto;

import com.github.galpiii.galpi.domain.collection.entity.DataCompleteness;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchFailureCode;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunStatus;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunType;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchSource;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchTargetStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;
import com.github.galpiii.galpi.domain.pullrequest.entity.ChangeType;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;

import java.time.OffsetDateTime;

/** 기능대조 내부 조회용 투영을 한곳에 모은다. API 응답 DTO와 분리해 조회에 필요한 값만 전달한다. */
public final class FeatureMatchRows {

    private FeatureMatchRows() {
    }

    public record ProjectRow(long id, long ownerId, Long activeSpecDocumentId) {
    }

    public record RunRow(long id, long projectId, long specDocumentId, long userId,
                         FeatureMatchRunStatus status, String featureSnapshotHash, int featureCount,
                         int eligiblePrCount, int excludedFailedPrCount, int excludedCancelledPrCount,
                         FeatureMatchFailureCode failureCode, OffsetDateTime startedAt,
                         OffsetDateTime finishedAt, OffsetDateTime createdAt,
                         FeatureMatchRunType runType, Long baseRunId, String featureSnapshotJson) {
        public RunRow(long id, long projectId, long specDocumentId, long userId,
                      FeatureMatchRunStatus status, String featureSnapshotHash, int featureCount,
                      int eligiblePrCount, int excludedFailedPrCount, int excludedCancelledPrCount,
                      FeatureMatchFailureCode failureCode, OffsetDateTime startedAt,
                      OffsetDateTime finishedAt, OffsetDateTime createdAt) {
            this(id, projectId, specDocumentId, userId, status, featureSnapshotHash, featureCount,
                    eligiblePrCount, excludedFailedPrCount, excludedCancelledPrCount, failureCode,
                    startedAt, finishedAt, createdAt, FeatureMatchRunType.FULL, null, null);
        }
    }

    public record TargetRow(long id, long featureMatchRunId, long pullRequestAnalysisId,
                            String analysisHeadSha, String analysisSnapshotHash, String sourceSnapshotHash,
                            FeatureMatchTargetStatus status, String claimedBy, int attempts, String inputJson) {
        public TargetRow(long id, long featureMatchRunId, long pullRequestAnalysisId,
                         String analysisHeadSha, String analysisSnapshotHash,
                         FeatureMatchTargetStatus status, String claimedBy, int attempts) {
            this(id, featureMatchRunId, pullRequestAnalysisId, analysisHeadSha,
                    analysisSnapshotHash, null, status, claimedBy, attempts, null);
        }
    }

    public record FeatureRow(long id, Long sectionId, String sectionTitle, Integer sectionOrder,
                             String name, int displayOrder, FeatureReviewStatus reviewStatus,
                             Integer sourcePageStart, Integer sourcePageEnd) {
    }

    public record RequirementRow(long id, long featureId, String content, int displayOrder) {
    }

    public record PrRow(long id, long repositoryId, String fullName, int number, String title,
                        String body, String headSha, String authorLogin, OffsetDateTime mergedAt,
                        String htmlUrl, DataCompleteness dataCompleteness, Long analysisId,
                        PullRequestAnalysisStatus analysisStatus, String analysisHeadSha,
                        String summary, ChangeType changeType) {
    }

    public record MatchRow(long id, long featureId, long pullRequestId, FeatureMatchSource source,
                           String reason, Long userId, OffsetDateTime createdAt) {
    }

    public record RequirementLinkRow(long featurePrMatchId, long featureRequirementId) {
    }

    public record RepositoryRow(long id, String fullName) {
    }

    public record FileRow(String path, String changeStatus, int additions, int deletions) {
    }

    public record Counts(int pendingCount, int runningCount, int completedCount,
                         int failedCount, int cancelledCount) {
    }

    public record TargetOutcomeRow(long pullRequestId, FeatureMatchTargetStatus status) {
    }
}
