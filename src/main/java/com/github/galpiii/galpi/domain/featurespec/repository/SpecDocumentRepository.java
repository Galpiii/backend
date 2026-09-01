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

    // 교체 대상을 찾는다. 프로젝트당 문서는 하나이므로 UNIQUE 제약이 결과가 둘일 수 없음을 보장한다.
    Optional<SpecDocument> findByProjectId(Long projectId);

    /**
     * 교체할 문서를 지우고 실제로 지운 행 수를 돌려준다.
     *
     * <p>{@code deleteById}는 엔티티를 읽어 온 뒤 지우므로, 읽은 뒤 커밋 전에 다른 요청이 같은
     * 행을 지우면 Hibernate의 행 수 검사에 걸려 {@code ObjectOptimisticLockingFailureException}이
     * 난다. 그 예외는 핸들러의 폴백으로 흘러 500과 error 로그가 되는데, 실제로는 동시 요청이
     * 갈린 것뿐이라 409로 답해야 한다. 행 수를 직접 보고 판단할 수 있게 벌크 삭제를 쓴다.
     *
     * <p>연쇄 삭제는 {@code ON DELETE CASCADE}가 DB에서 처리하므로 벌크 삭제여도 그대로 동작한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from SpecDocument specDocument
             where specDocument.id = :specDocumentId
            """)
    int deleteByIdReturningCount(@Param("specDocumentId") Long specDocumentId);

    @Query("""
            select specDocument
              from SpecDocument specDocument
             where specDocument.id = :specDocumentId
               and specDocument.project.owner.id = :userId
               and specDocument.project.deletedAt is null
            """)
    Optional<SpecDocument> findOwned(@Param("specDocumentId") Long specDocumentId,
                                     @Param("userId") Long userId);

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
