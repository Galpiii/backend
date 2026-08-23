package com.github.galpiii.galpi.domain.analysis.repository;

import com.github.galpiii.galpi.domain.analysis.entity.AnalysisConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnalysisConfigRepository extends JpaRepository<AnalysisConfig, Long> {

    /** 저장소별 설정. 없으면 프로젝트 기본값으로 내려간다. */
    Optional<AnalysisConfig> findByProjectIdAndRepositoryId(Long projectId, Long repositoryId);

    /** {@code repository_id}가 NULL인 프로젝트 기본값. 부분 유니크 인덱스가 하나만 있도록 보장한다. */
    Optional<AnalysisConfig> findByProjectIdAndRepositoryIsNull(Long projectId);
}
