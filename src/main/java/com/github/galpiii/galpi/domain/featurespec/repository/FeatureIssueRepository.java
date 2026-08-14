package com.github.galpiii.galpi.domain.featurespec.repository;

import com.github.galpiii.galpi.domain.featurespec.entity.FeatureIssue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureIssueRepository extends JpaRepository<FeatureIssue, Long> {
}
