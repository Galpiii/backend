package com.github.galpiii.galpi.domain.featurematch.dto.response;

import com.github.galpiii.galpi.domain.pullrequest.entity.ChangeType;
import com.github.galpiii.galpi.domain.collection.entity.DataCompleteness;

import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchPullRequestRow;

import java.time.OffsetDateTime;

public record FeatureMatchPullRequestResponse(long pullRequestId, long repositoryId, String repositoryFullName,
                                              int number, String title, String state, String authorLogin,
                                              OffsetDateTime mergedAt, String htmlUrl,
                                              String analysisSummary, ChangeType changeType,
                                              DataCompleteness dataCompleteness) {
    public static FeatureMatchPullRequestResponse from(FeatureMatchPullRequestRow pr) {
        return new FeatureMatchPullRequestResponse(pr.id(), pr.repositoryId(), pr.fullName(), pr.number(), pr.title(),
                "MERGED", pr.authorLogin(), pr.mergedAt(), pr.htmlUrl(), pr.summary(), pr.changeType(), pr.dataCompleteness());
    }
}
