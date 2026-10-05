package com.github.galpiii.galpi.domain.featurematch.repository;

import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeatureMatchRunRepository extends JpaRepository<FeatureMatchRun, Long> {

    List<FeatureMatchRun> findAllByProjectIdAndIdNot(long projectId, long id);
}
