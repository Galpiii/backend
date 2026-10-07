package com.github.galpiii.galpi.domain.featurematch.repository;

import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchCurrentState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface FeatureMatchCurrentStateRepository extends JpaRepository<FeatureMatchCurrentState, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from FeatureMatchCurrentState state where state.projectId = :projectId")
    Optional<FeatureMatchCurrentState> lockByProjectId(@Param("projectId") long projectId);
}
