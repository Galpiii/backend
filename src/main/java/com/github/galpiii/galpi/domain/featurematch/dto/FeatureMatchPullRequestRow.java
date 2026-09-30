package com.github.galpiii.galpi.domain.featurematch.dto;

import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.domain.pullrequest.entity.ChangeType;
import com.github.galpiii.galpi.domain.collection.entity.DataCompleteness;

import java.time.OffsetDateTime;

/**
 * 기능대조 조회에 필요한 값만 담는 불변 투영.
 */
public record FeatureMatchPullRequestRow(long id, long repositoryId, String fullName, int number, String title,
                                         String body, String headSha, String authorLogin, OffsetDateTime mergedAt,
                                         String htmlUrl, DataCompleteness dataCompleteness, Long analysisId,
                                         PullRequestAnalysisStatus analysisStatus,
                                         String analysisHeadSha, String summary, ChangeType changeType) {
}

