package com.github.galpiii.galpi.domain.featurespec.repository;

import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionFailureCode;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface SpecDocumentRepository extends JpaRepository<SpecDocument, Long> {

    boolean existsByProjectId(Long projectId);

    // 프로젝트 접근 권한을 확인한 뒤 호출한다. 다른 프로젝트의 문서를 id만으로 열람하지 못하게 막는다.
    Optional<SpecDocument> findByIdAndProjectId(Long id, Long projectId);

    @Modifying(clearAutomatically = true)
    @Query("""
            update SpecDocument specDocument
            set specDocument.extractionStatus = :failed,
                specDocument.failureCode = :failureCode
            where specDocument.extractionStatus in :inProgressStatuses
            """)
    int failAll(
            @Param("inProgressStatuses") Collection<ExtractionStatus> inProgressStatuses,
            @Param("failed") ExtractionStatus failed,
            @Param("failureCode") ExtractionFailureCode failureCode
    );
}
