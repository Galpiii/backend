package com.github.galpiii.galpi.domain.featurespec.repository;

import com.github.galpiii.galpi.domain.featurespec.entity.DuplicateCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DuplicateCandidateRepository extends JpaRepository<DuplicateCandidate, Long> {

    List<DuplicateCandidate> findAllByFeatureIdIn(Collection<Long> featureIds);

    /**
     * 병합 요청이 실제로 AI가 지목한 관계인지 확인한다.
     *
     * <p>방향을 그대로 본다. 같은 중복 관계는 한 방향으로만 저장되고 배지도 그 방향의 기능에만
     * 붙으므로, 화면에서 병합을 시작할 수 있는 기능은 언제나 이 조건을 만족한다. 양방향으로
     * 열어 두면 배지가 없는 쪽에서도 병합이 통과해 "AI가 지목한 방향에서만 합친다"는 규칙이
     * 풀린다.
     *
     * <p>중복 후보는 같은 분석 안에서만 만들어지므로, 이 확인이 통과하면 두 기능이 같은
     * 문서에 속한다는 것도 함께 보장된다.
     */
    Optional<DuplicateCandidate> findByFeatureIdAndTargetFeatureId(Long featureId, Long targetFeatureId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from DuplicateCandidate candidate where candidate.feature.id in :featureIds")
    int deleteByFeatureIds(@Param("featureIds") Collection<Long> featureIds);

    /**
     * 이 기능을 상대로 지목한 후보를 지운다.
     *
     * <p>기능이 수정되면 그 기능을 가리키던 제안은 사라진 버전을 설명하게 된다. reason도
     * 병합 제안명도 수정 전 내용을 기준으로 만들어진 것이라, 남겨 두면 지목한 쪽 사용자가
     * 낡은 근거로 병합을 판단한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from DuplicateCandidate candidate where candidate.targetFeature.id in :featureIds")
    int deleteByTargetFeatureIds(@Param("featureIds") Collection<Long> featureIds);
}
