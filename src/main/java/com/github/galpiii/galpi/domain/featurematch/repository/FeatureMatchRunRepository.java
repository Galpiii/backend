package com.github.galpiii.galpi.domain.featurematch.repository;

import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeatureMatchRunRepository extends JpaRepository<FeatureMatchRun, Long> {

    /**
     * 프로젝트 잠금을 잡고 선행 조건을 검증한 뒤 호출한다. 하위 AI 결과는 DB FK로 함께 삭제된다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from FeatureMatchRun run where run.project.id = :projectId")
    int deleteAllForProject(@Param("projectId") Long projectId);
}

