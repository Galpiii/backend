package com.github.galpiii.galpi.domain.featurespec.support;

import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.DuplicateCandidate;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Feature;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Issue;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Section;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.SplitSuggestion;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.SuggestedFeature;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureIssueType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LLM 추출 결과를 그대로 저장할 수 있는 형태로 다듬는다.
 *
 * <p>Structured Output이 보장하는 것은 구조뿐이라 참조 무결성과 길이 제약은 여전히 깨질 수 있다.
 * 이런 위반은 프롬프트가 모호해서 생기는 계통적 실패에 가까워 다시 호출해도 같은 자리에서 또
 * 틀린다. 그래서 재시도하지 않고 깨진 조각만 버린 뒤 나머지를 저장한다.
 *
 * <p>기능과 세부 요구사항은 어떤 경우에도 버리지 않는다. 버려지는 것은 중복 후보, 분리 제안,
 * 분류 연결, 특이사항 배지뿐이라 PR 대조 정확도에는 영향이 없다.
 *
 * <p>검증하고 복구하는 항목은 다음과 같다.
 * <ul>
 *   <li>이름·섹션명이 255자를 넘음 → 자른다. 원문을 담는 sourceTitle은 TEXT라 자르지 않는다
 *   <li>섹션명이 중복됨 → 먼저 온 섹션만 남긴다. 기능이 어느 섹션에 속하는지 정할 수 없다
 *   <li>기능의 섹션이 어느 섹션명과도 맞지 않음 → 미분류로 둔다
 *   <li>특이사항 유형을 알 수 없거나 한 기능에 같은 유형이 겹침 → 버린다
 *   <li>중복 후보가 없는 기능·자기 자신·중복된 extractionId를 가리킴 → 그 후보만 버린다
 *   <li>분리 제안이 요구사항을 누락·중복해 배정하거나 후보가 2개 미만 → 제안 전체를 버린다
 *   <li>특이사항 배지와 실제 데이터의 짝이 맞지 않음 → 양쪽을 모두 버린다
 * </ul>
 *
 * <p>통과한 결과는 위 항목을 모두 만족하므로 저장 계층이 그대로 믿고 쓸 수 있다.
 */
@Slf4j
@Component
public class FeatureExtractionResultNormalizer {

    private static final int MAX_NAME_LENGTH = 255;
    private static final int MIN_SUGGESTED_FEATURES = 2;

    private static final Set<String> KNOWN_ISSUE_TYPES = Arrays.stream(FeatureIssueType.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    public FeatureSpecExtractionResult normalize(Long specDocumentId, FeatureSpecExtractionResult raw) {
        List<Section> sections = normalizeSections(specDocumentId, nullToEmpty(raw.sections()));
        Set<String> sectionTitles = sections.stream().map(Section::title).collect(Collectors.toSet());

        List<Feature> rawFeatures = nullToEmpty(raw.features());
        Set<String> referableIds = referableExtractionIds(specDocumentId, rawFeatures);

        List<Feature> features = rawFeatures.stream()
                .map(feature -> normalizeFeature(specDocumentId, feature, sectionTitles, referableIds))
                .toList();

        return new FeatureSpecExtractionResult(sections, features);
    }

    private List<Section> normalizeSections(Long specDocumentId, List<Section> sections) {
        Set<String> seenTitles = new HashSet<>();
        List<Section> normalized = new ArrayList<>();

        for (Section section : sections) {
            String title = truncate(specDocumentId, "섹션명", section.title());

            if (!seenTitles.add(title)) {
                log.warn(
                        "[기능명세서 분석] 섹션명이 중복되어 뒤에 온 섹션을 버렸습니다. specDocumentId: {}, title: {}",
                        specDocumentId,
                        title
                );
                continue;
            }

            normalized.add(new Section(title, section.sourceTitle(), section.pageStart(), section.pageEnd()));
        }

        return normalized;
    }

    private Set<String> referableExtractionIds(Long specDocumentId, List<Feature> features) {
        Map<String, Long> countById = features.stream()
                .collect(Collectors.groupingBy(Feature::extractionId, Collectors.counting()));

        countById.forEach((id, count) -> {
            if (count > 1) {
                log.warn(
                        "[기능명세서 분석] extractionId가 중복되어 이 id를 향한 참조를 버립니다. specDocumentId: {}, extractionId: {}",
                        specDocumentId,
                        id
                );
            }
        });

        return countById.entrySet().stream()
                .filter(entry -> entry.getValue() == 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private Feature normalizeFeature(
            Long specDocumentId,
            Feature feature,
            Set<String> sectionTitles,
            Set<String> referableIds
    ) {
        String name = truncate(specDocumentId, "기능명", feature.name());
        String section = resolveSection(specDocumentId, feature, sectionTitles);
        List<FeatureSpecExtractionResult.Requirement> requirements = nullToEmpty(feature.requirements());

        List<DuplicateCandidate> duplicateCandidates =
                normalizeDuplicateCandidates(specDocumentId, feature, referableIds);
        SplitSuggestion splitSuggestion =
                normalizeSplitSuggestion(specDocumentId, feature, requirements.size());
        Set<String> issueTypes = normalizeIssueTypes(specDocumentId, feature);

        boolean hasDuplicateIssue = issueTypes.contains(FeatureIssueType.DUPLICATE_SUSPECTED.name());

        if (hasDuplicateIssue != !duplicateCandidates.isEmpty()) {
            logMismatch(specDocumentId, feature, FeatureIssueType.DUPLICATE_SUSPECTED);
            duplicateCandidates = List.of();
            issueTypes.remove(FeatureIssueType.DUPLICATE_SUSPECTED.name());
        }

        boolean hasSplitIssue = issueTypes.contains(FeatureIssueType.SPLIT_RECOMMENDED.name());

        if (hasSplitIssue != (splitSuggestion != null)) {
            logMismatch(specDocumentId, feature, FeatureIssueType.SPLIT_RECOMMENDED);
            splitSuggestion = null;
            issueTypes.remove(FeatureIssueType.SPLIT_RECOMMENDED.name());
        }

        if (!requirements.isEmpty() && issueTypes.remove(FeatureIssueType.MISSING_REQUIREMENTS.name())) {
            logMismatch(specDocumentId, feature, FeatureIssueType.MISSING_REQUIREMENTS);
        }

        List<Issue> issues = pickIssues(feature, issueTypes);

        return new Feature(
                feature.extractionId(),
                name,
                section,
                requirements,
                feature.source(),
                issues,
                duplicateCandidates,
                splitSuggestion
        );
    }

    private List<Issue> pickIssues(Feature feature, Set<String> survivingTypes) {
        Set<String> used = new HashSet<>();
        List<Issue> issues = new ArrayList<>();

        for (Issue issue : nullToEmpty(feature.issues())) {
            if (survivingTypes.contains(issue.type()) && used.add(issue.type())) {
                issues.add(issue);
            }
        }

        return issues;
    }

    private String resolveSection(Long specDocumentId, Feature feature, Set<String> sectionTitles) {
        String section = truncate(specDocumentId, "기능의 섹션명", feature.section());

        if (section == null || sectionTitles.contains(section)) {
            return section;
        }

        log.warn(
                "[기능명세서 분석] 기능의 섹션을 찾을 수 없어 미분류로 둡니다. specDocumentId: {}, extractionId: {}, section: {}",
                specDocumentId,
                feature.extractionId(),
                section
        );

        return null;
    }

    private List<DuplicateCandidate> normalizeDuplicateCandidates(
            Long specDocumentId,
            Feature feature,
            Set<String> referableIds
    ) {
        List<DuplicateCandidate> normalized = new ArrayList<>();
        Set<String> seenTargets = new HashSet<>();

        for (DuplicateCandidate candidate : nullToEmpty(feature.duplicateCandidates())) {
            String target = candidate.targetExtractionId();

            if (target == null
                    || target.equals(feature.extractionId())
                    || !referableIds.contains(target)
                    || !seenTargets.add(target)) {
                log.warn(
                        "[기능명세서 분석] 가리킬 수 없는 중복 후보를 버렸습니다. specDocumentId: {}, extractionId: {}, target: {}",
                        specDocumentId,
                        feature.extractionId(),
                        target
                );
                continue;
            }

            normalized.add(new DuplicateCandidate(
                    target,
                    candidate.reason(),
                    truncate(specDocumentId, "병합 제안 기능명", candidate.suggestedMergedName()),
                    truncate(specDocumentId, "병합 제안 섹션명", candidate.suggestedSection())
            ));
        }

        return normalized;
    }

    private SplitSuggestion normalizeSplitSuggestion(
            Long specDocumentId,
            Feature feature,
            int requirementCount
    ) {
        SplitSuggestion suggestion = feature.splitSuggestion();

        if (suggestion == null) {
            return null;
        }

        List<SuggestedFeature> suggestedFeatures = nullToEmpty(suggestion.suggestedFeatures());

        if (!coversAllRequirementsExactlyOnce(suggestedFeatures, requirementCount)) {
            log.warn(
                    "[기능명세서 분석] 요구사항 배정이 맞지 않아 분리 제안을 버렸습니다. specDocumentId: {}, extractionId: {}",
                    specDocumentId,
                    feature.extractionId()
            );
            return null;
        }

        return new SplitSuggestion(suggestedFeatures.stream()
                .map(suggested -> new SuggestedFeature(
                        truncate(specDocumentId, "분리 제안 기능명", suggested.suggestedName()),
                        suggested.requirementIndexes(),
                        truncate(specDocumentId, "분리 제안 섹션명", suggested.suggestedSection())
                ))
                .toList());
    }

    private boolean coversAllRequirementsExactlyOnce(List<SuggestedFeature> suggestedFeatures, int requirementCount) {
        if (suggestedFeatures.size() < MIN_SUGGESTED_FEATURES || requirementCount == 0) {
            return false;
        }

        Set<Integer> assigned = new HashSet<>();
        int total = 0;

        for (SuggestedFeature suggested : suggestedFeatures) {
            List<Integer> indexes = nullToEmpty(suggested.requirementIndexes());

            if (indexes.isEmpty()) {
                return false;
            }

            for (Integer index : indexes) {
                if (index == null || index < 0 || index >= requirementCount) {
                    return false;
                }
                assigned.add(index);
                total++;
            }
        }

        return assigned.size() == requirementCount && total == requirementCount;
    }

    private Set<String> normalizeIssueTypes(Long specDocumentId, Feature feature) {
        Set<String> types = new LinkedHashSet<>();

        for (Issue issue : nullToEmpty(feature.issues())) {
            if (!KNOWN_ISSUE_TYPES.contains(issue.type())) {
                log.warn(
                        "[기능명세서 분석] 알 수 없는 특이사항 유형을 버렸습니다. specDocumentId: {}, extractionId: {}, type: {}",
                        specDocumentId,
                        feature.extractionId(),
                        issue.type()
                );
                continue;
            }

            types.add(issue.type());
        }

        return types;
    }

    private void logMismatch(Long specDocumentId, Feature feature, FeatureIssueType issueType) {
        log.warn(
                "[기능명세서 분석] 특이사항과 실제 데이터가 어긋나 양쪽을 모두 버렸습니다. specDocumentId: {}, extractionId: {}, type: {}",
                specDocumentId,
                feature.extractionId(),
                issueType
        );
    }

    private String truncate(Long specDocumentId, String fieldName, String value) {
        if (value == null || value.length() <= MAX_NAME_LENGTH) {
            return value;
        }

        log.warn(
                "[기능명세서 분석] {}이 {}자를 넘어 잘랐습니다. specDocumentId: {}, length: {}",
                fieldName,
                MAX_NAME_LENGTH,
                specDocumentId,
                value.length()
        );

        return value.substring(0, MAX_NAME_LENGTH);
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
