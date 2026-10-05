package com.github.galpiii.galpi.domain.featurematch.repository;

import com.github.galpiii.galpi.domain.featurematch.entity.FeaturePrMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

public interface FeaturePrMatchRepository extends JpaRepository<FeaturePrMatch, Long> {
    @Modifying(flushAutomatically = true)
    @Query("delete from FeaturePrMatch match where match.source = 'AI' and match.pullRequest.repository.project.id = :projectId and match.pullRequest.id in :prIds")
    int deleteAiByProjectAndPullRequestIds(@Param("projectId") long projectId, @Param("prIds") Collection<Long> prIds);
}
