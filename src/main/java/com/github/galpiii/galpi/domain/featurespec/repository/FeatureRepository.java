package com.github.galpiii.galpi.domain.featurespec.repository;

import com.github.galpiii.galpi.domain.featurespec.entity.Feature;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureRepository extends JpaRepository<Feature, Long> {
}
