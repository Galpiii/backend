package com.github.galpiii.galpi.domain.featurespec.repository;

import com.github.galpiii.galpi.domain.featurespec.entity.FeatureSection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface FeatureSectionRepository extends JpaRepository<FeatureSection, Long> {

    /** 병합·분리가 제안한 분류명이 이미 있는 분류인지 본다. 문서 안에서 title은 유일하다. */
    List<FeatureSection> findAllBySpecDocumentIdAndTitleIn(Long specDocumentId, Collection<String> titles);

    /** 검토로 새로 만드는 분류는 기존 분류 뒤에 붙인다. */
    List<FeatureSection> findTop1BySpecDocumentIdOrderByDisplayOrderDescIdDesc(Long specDocumentId);
}
