package com.github.galpiii.galpi.domain.featurespec.repository;

import com.github.galpiii.galpi.domain.featurespec.entity.FeatureRequirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface FeatureRequirementRepository extends JpaRepository<FeatureRequirement, Long> {

    List<FeatureRequirement> findAllByFeatureIdOrderByDisplayOrderAscIdAsc(Long featureId);

    List<FeatureRequirement> findAllByFeatureIdInOrderByDisplayOrderAscIdAsc(Collection<Long> featureIds);
}
