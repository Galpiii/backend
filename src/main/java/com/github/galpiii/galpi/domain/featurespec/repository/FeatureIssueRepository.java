package com.github.galpiii.galpi.domain.featurespec.repository;

import com.github.galpiii.galpi.domain.featurespec.entity.FeatureIssue;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureIssueType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface FeatureIssueRepository extends JpaRepository<FeatureIssue, Long> {

    List<FeatureIssue> findAllByFeatureIdIn(Collection<Long> featureIds);

    /** 특이사항이 하나라도 있는 기능. 목록 필터가 "확인 필요"를 가려내는 데 쓴다. */
    @Query("select distinct issue.feature.id from FeatureIssue issue where issue.feature.specDocument.id = :specDocumentId")
    List<Long> findFeatureIdsWithIssue(@Param("specDocumentId") Long specDocumentId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from FeatureIssue issue where issue.feature.id in :featureIds")
    int deleteByFeatureIds(@Param("featureIds") Collection<Long> featureIds);

    /**
     * 합칠 상대가 사라진 중복 의심 배지를 지운다.
     *
     * <p>{@code duplicate_candidates}는 가리키는 쪽과 가리켜지는 쪽 모두에 ON DELETE CASCADE가
     * 걸려 있다. 그래서 기능 A를 지우면 A를 가리키던 D→A 행까지 함께 사라지는데, D의 배지는
     * D에 매달려 있어 그대로 남는다. 남은 배지는 화면에 "중복 의심"을 띄우면서 정작 합칠
     * 상대를 보여 주지 못한다.
     *
     * <p>손대지도 않은 기능이 남의 삭제에 휘말린 것이라, 검토를 끝낼 때 지우는 규칙으로는
     * 걸리지 않는다. 기능을 실제로 지우는 작업(삭제·병합·분리)마다 따로 정리해야 한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from FeatureIssue issue
             where issue.issueType = :duplicateSuspected
               and issue.feature.specDocument.id = :specDocumentId
               and not exists (select 1 from DuplicateCandidate candidate
                                where candidate.feature = issue.feature)
            """)
    int deleteOrphanDuplicateIssues(@Param("specDocumentId") Long specDocumentId,
                                    @Param("duplicateSuspected") FeatureIssueType duplicateSuspected);
}
