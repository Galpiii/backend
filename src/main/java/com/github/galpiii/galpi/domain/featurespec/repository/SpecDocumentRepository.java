package com.github.galpiii.galpi.domain.featurespec.repository;

import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpecDocumentRepository extends JpaRepository<SpecDocument, Long> {

    boolean existsByProjectId(Long projectId);

    // 프로젝트 접근 권한을 확인한 뒤 호출한다. 다른 프로젝트의 문서를 id만으로 열람하지 못하게 막는다.
    Optional<SpecDocument> findByIdAndProjectId(Long id, Long projectId);
}
