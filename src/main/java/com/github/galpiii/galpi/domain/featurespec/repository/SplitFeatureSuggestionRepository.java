package com.github.galpiii.galpi.domain.featurespec.repository;

import com.github.galpiii.galpi.domain.featurespec.entity.SplitFeatureSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SplitFeatureSuggestionRepository extends JpaRepository<SplitFeatureSuggestion, Long> {

    List<SplitFeatureSuggestion> findAllByFeatureIdOrderByDisplayOrderAscIdAsc(Long featureId);

    List<SplitFeatureSuggestion> findAllByFeatureIdInOrderByDisplayOrderAscIdAsc(Collection<Long> featureIds);

    /** 연결된 {@code split_suggestion_requirements}는 DB의 ON DELETE CASCADE가 함께 지운다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SplitFeatureSuggestion suggestion where suggestion.feature.id in :featureIds")
    int deleteByFeatureIds(@Param("featureIds") Collection<Long> featureIds);
}
