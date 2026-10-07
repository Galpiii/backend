package com.github.galpiii.galpi.domain.featurematch.repository;

import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchCurrentPullRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeatureMatchCurrentPullRequestRepository extends JpaRepository<FeatureMatchCurrentPullRequest, Long> {
    List<FeatureMatchCurrentPullRequest> findAllByProjectId(long projectId);

    void deleteAllByProjectId(long projectId);
}
