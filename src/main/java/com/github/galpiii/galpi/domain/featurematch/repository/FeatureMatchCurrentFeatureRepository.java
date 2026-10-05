package com.github.galpiii.galpi.domain.featurematch.repository;

import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchCurrentFeature;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeatureMatchCurrentFeatureRepository extends JpaRepository<FeatureMatchCurrentFeature, Long> {
    List<FeatureMatchCurrentFeature> findAllByProjectId(long projectId);

    void deleteAllByProjectId(long projectId);
}
