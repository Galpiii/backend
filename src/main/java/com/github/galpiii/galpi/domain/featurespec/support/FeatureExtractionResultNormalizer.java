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
import java.util.Objects;
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
 *   <li>이름·섹션명에 앞뒤 공백이 붙음 → 뗀다. 이 값들은 분류를 찾는 키로 쓰인다
 *   <li>이름·섹션명이 255자를 넘음 → 자른다. 원문을 담는 sourceTitle은 TEXT라 자르지 않는다
 *   <li>섹션명이 중복됨 → 먼저 온 섹션만 남긴다. 기능이 어느 섹션에 속하는지 정할 수 없다
 *   <li>기능의 섹션이 어느 섹션명과도 맞지 않음 → 미분류로 둔다
 *   <li>특이사항 유형을 알 수 없거나 한 기능에 같은 유형이 겹침 → 버린다
 *   <li>중복 후보가 없는 기능·자기 자신·중복된 extractionId를 가리킴 → 그 후보만 버린다
 *   <li>같은 중복 관계가 양방향(A→B, B→A)으로 옴 → 배지가 붙은 방향 중 먼저 온 것만 남긴다
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

        // 이미 한 방향으로 처리한 중복 쌍. 기능을 넘나들며 쌓이므로 스트림에 얹지 않고 밖에 둔다.
        Set<List<String>> claimedPairs = new HashSet<>();
        List<Feature> features = new ArrayList<>();

        for (Feature feature : rawFeatures) {
            features.add(normalizeFeature(
                    specDocumentId, feature, sectionTitles, referableIds, claimedPairs));
        }

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
        // groupingBy는 null 키를 허용하지 않아 extractionId가 비어 있으면 정규화 전체가 죽는다.
        Map<String, Long> countById = features.stream()
                .map(Feature::extractionId)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(id -> id, Collectors.counting()));

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
            Set<String> referableIds,
            Set<List<String>> claimedPairs
    ) {
        String name = truncate(specDocumentId, "기능명", feature.name());
        String section = resolveSection(specDocumentId, feature, sectionTitles);
        List<FeatureSpecExtractionResult.Requirement> requirements = nullToEmpty(feature.requirements());

        Set<String> issueTypes = normalizeIssueTypes(specDocumentId, feature);
        boolean hasDuplicateIssue = issueTypes.contains(FeatureIssueType.DUPLICATE_SUSPECTED.name());
        List<DuplicateCandidate> duplicateCandidates = List.of();

        if (hasDuplicateIssue) {
            duplicateCandidates =
                    normalizeDuplicateCandidates(specDocumentId, feature, referableIds, claimedPairs);

            if (duplicateCandidates.isEmpty()) {
                logMismatch(specDocumentId, feature, FeatureIssueType.DUPLICATE_SUSPECTED);
                issueTypes.remove(FeatureIssueType.DUPLICATE_SUSPECTED.name());
            }
        } else if (!nullToEmpty(feature.duplicateCandidates()).isEmpty()) {
            logMismatch(specDocumentId, feature, FeatureIssueType.DUPLICATE_SUSPECTED);
        }

        SplitSuggestion splitSuggestion =
                normalizeSplitSuggestion(specDocumentId, feature, requirements.size());

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
                nullToSource(feature.source()),
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

    /**
     * 같은 중복 관계가 양방향으로 오면 먼저 온 방향만 남긴다.
     *
     * <p>프롬프트는 한 방향만 반환하라고 하지만 구조로 강제할 수 없다. 양쪽이 다 저장되면
     * 배지가 두 기능에 붙어 사용자가 같은 중복을 두 번 확인하게 되고, 한쪽에서 "별도 기능으로
     * 유지"를 골라도 반대쪽이 다시 묻는다.
     *
     * <p>버려진 쪽 기능의 후보가 0개가 되면 호출부의 배지-데이터 짝 검사가 그 기능의
     * {@code DUPLICATE_SUSPECTED}까지 함께 버린다.
     */
    private List<DuplicateCandidate> normalizeDuplicateCandidates(
            Long specDocumentId,
            Feature feature,
            Set<String> referableIds,
            Set<List<String>> claimedPairs
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

            // extractionId가 없는 기능은 referableIds에 들지 못해 다른 기능이 가리킬 수 없다.
            // 반대 방향이 존재할 수 없으므로 대칭 검사에서 빼고, 쌍을 식별할 수단도 없다.
            String extractionId = feature.extractionId();

            if (extractionId != null && !claimedPairs.add(pairKey(extractionId, target))) {
                log.warn(
                        "[기능명세서 분석] 같은 중복 관계가 양방향으로 와서 뒤에 온 방향을 버렸습니다. specDocumentId: {}, extractionId: {}, target: {}",
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

    /**
     * 방향과 무관하게 같은 쌍이면 같은 키가 나온다.
     *
     * <p>두 id를 이어 붙인 문자열이 아니라 목록으로 만든다. 이어 붙이면 구분자가 필요한데,
     * extractionId는 LLM이 만든 문자열이라 어떤 구분자도 등장하지 않는다고 장담할 수 없고,
     * 겹치면 서로 다른 쌍이 같은 키로 접힌다.
     */
    private List<String> pairKey(String extractionId, String targetExtractionId) {
        return extractionId.compareTo(targetExtractionId) <= 0
                ? List.of(extractionId, targetExtractionId)
                : List.of(targetExtractionId, extractionId);
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

    /**
     * 페이지 정보가 통째로 비어 있어도 저장 계층이 역참조할 수 있게 빈 값으로 채운다.
     *
     * <p>스키마상 필수라 오기 어렵지만, 여기를 통과한 결과는 그대로 믿고 쓴다는 것이 이 계층의
     * 약속이다. 뚫리면 저장 트랜잭션 전체가 롤백되어 분석 한 건이 통째로 날아간다.
     */
    private FeatureSpecExtractionResult.Source nullToSource(FeatureSpecExtractionResult.Source source) {
        return source == null ? new FeatureSpecExtractionResult.Source(null, null) : source;
    }

    /**
     * 앞뒤 공백을 떼고 길이를 맞춘다.
     *
     * <p>공백을 떼는 것이 길이보다 중요하다. 이 값들은 이름이 아니라 <b>키</b>로 쓰인다 —
     * 기능은 섹션명 문자열로 분류에 연결되고, 검토 단계의 병합·분리는 suggestedSection으로
     * 기존 분류를 찾는다. 공백이 붙은 채 저장되면 같은 분류를 찾지 못해 분류가 미분류로
     * 떨어지거나 같은 이름의 분류가 하나 더 생기며, 둘 다 예외 없이 조용히 벌어진다.
     */
    private String truncate(Long specDocumentId, String fieldName, String value) {
        if (value == null) {
            return null;
        }

        String stripped = value.strip();

        if (stripped.length() <= MAX_NAME_LENGTH) {
            return stripped;
        }

        log.warn(
                "[기능명세서 분석] {}이 {}자를 넘어 잘랐습니다. specDocumentId: {}, length: {}",
                fieldName,
                MAX_NAME_LENGTH,
                specDocumentId,
                stripped.length()
        );

        // 자른 자리가 공백일 수 있다. 그대로 두면 다시 공백이 붙은 키가 된다.
        return stripped.substring(0, MAX_NAME_LENGTH).strip();
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
