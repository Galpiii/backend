package com.github.galpiii.galpi.domain.featurespec.repository;

import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionFailureCode;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;

public interface SpecDocumentRepository extends JpaRepository<SpecDocument, Long> {

    boolean existsByProjectId(Long projectId);

    // 프로젝트 접근 권한을 확인한 뒤 호출한다. 다른 프로젝트의 문서를 id만으로 열람하지 못하게 막는다.
    Optional<SpecDocument> findByIdAndProjectId(Long id, Long projectId);

    /**
     * 살아있는 서버가 처리 중일 수 없을 만큼 오래된 것만 실패로 바꾼다.
     *
     * <p>상태만 보고 바꾸면 다른 서버가 지금 분석 중인 문서까지 죽인다. 배포 중 새 서버가 구
     * 서버보다 먼저 뜨면 인스턴스가 한 대인 구성에서도 같은 일이 벌어진다.
     *
     * <p>벌크 update는 영속성 컨텍스트를 우회해 감사 필드가 채워지지 않으므로 updatedAt을 직접
     * 넣는다. 이 값이 곧 다음 판단의 기준이 되기 때문에 비워 두면 나이를 알 수 없게 된다.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update SpecDocument specDocument
            set specDocument.extractionStatus = :failed,
                specDocument.failureCode = :failureCode,
                specDocument.updatedAt = :now
            where specDocument.extractionStatus in :inProgressStatuses
              and specDocument.updatedAt < :staleBefore
            """)
    int failStale(
            @Param("inProgressStatuses") Collection<ExtractionStatus> inProgressStatuses,
            @Param("failed") ExtractionStatus failed,
            @Param("failureCode") ExtractionFailureCode failureCode,
            @Param("staleBefore") OffsetDateTime staleBefore,
            @Param("now") OffsetDateTime now
    );
}
