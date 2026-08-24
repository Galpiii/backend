package com.github.galpiii.galpi.domain.featurespec.repository;

import com.github.galpiii.galpi.domain.featurespec.entity.Feature;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FeatureRepository extends JpaRepository<Feature, Long> {

    /**
     * 소유자·프로젝트·문서를 한 번에 확인한다.
     *
     * <p>없는 기능, 남의 기능, 삭제된 프로젝트의 기능이 모두 같은 404가 되어야 id를 하나씩
     * 넣어 보는 것으로 남의 것을 알아낼 수 없다.
     */
    @Query("""
            select feature
              from Feature feature
             where feature.id = :featureId
               and feature.specDocument.id = :specDocumentId
               and feature.specDocument.project.id = :projectId
               and feature.specDocument.project.owner.id = :userId
               and feature.specDocument.project.deletedAt is null
            """)
    Optional<Feature> findOwned(@Param("featureId") Long featureId,
                                @Param("specDocumentId") Long specDocumentId,
                                @Param("projectId") Long projectId,
                                @Param("userId") Long userId);

    /**
     * 문서의 기능 전체를 화면 순서대로.
     *
     * <p>id를 2차 정렬로 둔다. 분리로 생긴 기능들은 원본의 순서를 그대로 물려받아 값이
     * 겹치므로, 이것이 없으면 형제들끼리 순서가 흔들린다.
     *
     * <p>분류를 함께 읽는다. 응답을 분류로 묶으려면 제목이 필요한데 지연 로딩으로 두면
     * 분류 수만큼 조회가 더 나간다. 다대일이라 조인으로 행이 늘지도 않는다.
     */
    @Query("""
            select feature
              from Feature feature
              left join fetch feature.section
             where feature.specDocument.id = :specDocumentId
             order by feature.displayOrder asc, feature.id asc
            """)
    List<Feature> findAllForReview(@Param("specDocumentId") Long specDocumentId);

    long countBySpecDocumentId(Long specDocumentId);

    long countBySpecDocumentIdAndReviewStatusNot(Long specDocumentId, FeatureReviewStatus reviewStatus);

    @Query("""
            select count(feature)
              from Feature feature
             where feature.specDocument.id = :specDocumentId
               and exists (select 1 from FeatureIssue issue where issue.feature = feature)
            """)
    long countReviewRequired(@Param("specDocumentId") Long specDocumentId);

    /**
     * 병합·분리·삭제가 기존 기능을 지운다.
     *
     * <p>지운 행 수를 돌려주는 것이 동시성 가드다. 같은 요청이 두 번 들어오면 늦은 쪽은
     * 이미 사라진 행을 지우려 해 0을 받고, 호출부가 그것을 보고 트랜잭션을 되돌린다.
     * 이 확인이 없으면 병합 결과가 두 개 생긴다.
     *
     * <p>{@code spec_document_id}를 조건에 함께 넣어 다른 문서의 기능이 섞여 들어와도
     * 지워지지 않게 한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from Feature feature
             where feature.id in :featureIds
               and feature.specDocument.id = :specDocumentId
            """)
    int deleteByIds(@Param("featureIds") Collection<Long> featureIds,
                    @Param("specDocumentId") Long specDocumentId);

    /**
     * 아직 확인하지 않은 기능을 한 번에 승인한다.
     *
     * <p>벌크 update는 영속성 컨텍스트를 우회해 감사 필드가 채워지지 않으므로 updatedAt을
     * 직접 넣는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Feature feature
               set feature.reviewStatus = :confirmed,
                   feature.updatedAt = :now
             where feature.specDocument.id = :specDocumentId
               and feature.reviewStatus = :unreviewed
            """)
    int confirmAllUnreviewed(@Param("specDocumentId") Long specDocumentId,
                             @Param("unreviewed") FeatureReviewStatus unreviewed,
                             @Param("confirmed") FeatureReviewStatus confirmed,
                             @Param("now") OffsetDateTime now);

    /** 아직 확인하지 않은 기능의 id. 일괄 승인이 이 기능들의 AI 산출물을 함께 지운다. */
    @Query("""
            select feature.id
              from Feature feature
             where feature.specDocument.id = :specDocumentId
               and feature.reviewStatus = :unreviewed
            """)
    List<Long> findIdsByReviewStatus(@Param("specDocumentId") Long specDocumentId,
                                     @Param("unreviewed") FeatureReviewStatus unreviewed);
}
