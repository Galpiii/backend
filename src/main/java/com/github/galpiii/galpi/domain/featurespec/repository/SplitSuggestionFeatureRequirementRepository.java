package com.github.galpiii.galpi.domain.featurespec.repository;

import com.github.galpiii.galpi.domain.featurespec.entity.FeatureRequirement;
import com.github.galpiii.galpi.domain.featurespec.entity.SplitSuggestionFeatureRequirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public interface SplitSuggestionFeatureRequirementRepository
        extends JpaRepository<SplitSuggestionFeatureRequirement, Long> {

    /**
     * 추천 기능이 가져갈 요구사항.
     *
     * <p>요구사항을 함께 읽는다. 지연 로딩으로 두면 내용을 꺼낼 때 연결 하나마다 조회가
     * 나가는데, 이 메서드는 목록 조회가 문서의 모든 분리 추천안에 대해 부르는 자리다.
     *
     * <p>정렬은 요구사항의 표시 순서를 따른다. 미리보기가 원문 순서와 어긋나면 사용자가
     * 무엇이 어디로 가는지 알아보기 어렵다.
     */
    @Query("""
            select link
              from SplitSuggestionFeatureRequirement link
              join fetch link.featureRequirement requirement
             where link.splitFeatureSuggestion.id in :suggestionIds
             order by requirement.displayOrder asc, requirement.id asc
            """)
    List<SplitSuggestionFeatureRequirement> findAllWithRequirement(
            @Param("suggestionIds") Collection<Long> suggestionIds);

    /** 추천안별로 가져갈 요구사항. 목록 조회는 내용만, 분리 실행은 복사할 원본으로 쓴다. */
    default Map<Long, List<FeatureRequirement>> findRequirementsBySuggestionId(
            Collection<Long> suggestionIds) {
        if (suggestionIds.isEmpty()) {
            return Map.of();
        }

        return findAllWithRequirement(suggestionIds).stream()
                .collect(Collectors.groupingBy(
                        link -> link.getSplitFeatureSuggestion().getId(),
                        LinkedHashMap::new,
                        Collectors.mapping(
                                SplitSuggestionFeatureRequirement::getFeatureRequirement,
                                Collectors.toList())));
    }
}
