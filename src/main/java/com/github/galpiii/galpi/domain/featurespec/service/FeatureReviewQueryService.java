package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.dto.FeatureReviewFilter;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureReviewResponse;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureReviewSummaryResponse;
import com.github.galpiii.galpi.domain.featurespec.entity.DuplicateCandidate;
import com.github.galpiii.galpi.domain.featurespec.entity.Feature;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureIssue;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureRequirement;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureSection;
import com.github.galpiii.galpi.domain.featurespec.entity.SplitFeatureSuggestion;
import com.github.galpiii.galpi.domain.featurespec.repository.DuplicateCandidateRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureIssueRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureRequirementRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.SplitFeatureSuggestionRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.SplitSuggestionFeatureRequirementRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 검토 화면의 기능 목록·집계 조회. */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureReviewQueryService {

    private final SpecDocumentRepository specDocumentRepository;
    private final FeatureRepository featureRepository;
    private final FeatureRequirementRepository featureRequirementRepository;
    private final FeatureIssueRepository featureIssueRepository;
    private final DuplicateCandidateRepository duplicateCandidateRepository;
    private final SplitFeatureSuggestionRepository splitFeatureSuggestionRepository;
    private final SplitSuggestionFeatureRequirementRepository splitSuggestionFeatureRequirementRepository;

    /**
     * 검토 화면의 기능 목록. 분류로 묶어서 준다.
     *
     * <p>필터를 SQL이 아니라 메모리에서 적용한다. 페이지를 나누지 않아 어차피 문서 전체를
     * 읽고, 특이사항이 있는 기능 집합은 응답에도 필요해 이미 손에 있다.
     */
    @Transactional(readOnly = true)
    public FeatureReviewResponse list(Long specDocumentId, Long userId, FeatureReviewFilter filter) {
        requireSpecDocument(specDocumentId, userId);

        List<Feature> features = featureRepository.findAllForReview(specDocumentId);
        Set<Long> withIssue = new HashSet<>(featureIssueRepository.findFeatureIdsWithIssue(specDocumentId));

        FeatureReviewFilter resolvedFilter = filter == null ? FeatureReviewFilter.ALL : filter;
        List<Feature> filtered = features.stream()
                .filter(feature -> matches(resolvedFilter, feature, withIssue))
                .toList();

        // 중복 후보가 가리키는 기능은 필터에서 걸러졌을 수 있다. 문서 전체를 이미 읽어 왔으므로
        // 여기서 찾으면 추가 조회 없이 상대 기능의 이름과 원문 페이지를 꺼낼 수 있다.
        Map<Long, Feature> featuresById = features.stream()
                .collect(Collectors.toMap(Feature::getId, Function.identity()));

        return new FeatureReviewResponse(groupBySection(filtered, featuresById));
    }

    @Transactional(readOnly = true)
    public FeatureReviewSummaryResponse summary(Long specDocumentId, Long userId) {
        requireSpecDocument(specDocumentId, userId);

        return FeatureReviewSummaryResponse.of(
                featureRepository.countBySpecDocumentId(specDocumentId),
                featureRepository.countReviewRequired(specDocumentId),
                featureRepository.countBySpecDocumentIdAndReviewStatusNot(
                        specDocumentId, FeatureReviewStatus.UNREVIEWED)
        );
    }

    private boolean matches(FeatureReviewFilter filter, Feature feature, Set<Long> withIssue) {
        return switch (filter) {
            case ALL -> true;
            case REVIEW_REQUIRED -> withIssue.contains(feature.getId());
            case NO_ISSUE -> feature.getReviewStatus() == FeatureReviewStatus.UNREVIEWED
                    && !withIssue.contains(feature.getId());
            case REVIEWED -> feature.getReviewStatus() != FeatureReviewStatus.UNREVIEWED;
        };
    }

    /**
     * 기능들을 분류로 묶는다. 자식 데이터는 기능 id 목록으로 한 번씩 모아 읽어 N+1을 만들지 않는다.
     *
     * <p>어느 분류에도 속하지 않는 기능은 맨 뒤에 따로 묶인다.
     */
    private List<FeatureReviewResponse.SectionGroup> groupBySection(List<Feature> features,
                                                                   Map<Long, Feature> featuresById) {
        if (features.isEmpty()) {
            return List.of();
        }

        List<Long> featureIds = features.stream().map(Feature::getId).toList();

        Map<Long, List<DuplicateCandidate>> candidates = groupByFeatureId(
                duplicateCandidateRepository.findAllByFeatureIdIn(featureIds),
                candidate -> candidate.getFeature().getId());

        // 중복 후보의 상대 기능은 필터에서 빠졌을 수 있다. 그 기능의 요구사항까지 함께 읽지
        // 않으면 비교 화면에 상대 쪽이 빈 채로 나가는데, 예외도 로그도 남지 않는다.
        List<Long> requirementOwnerIds = Stream.concat(
                        featureIds.stream(),
                        candidates.values().stream()
                                .flatMap(List::stream)
                                .map(candidate -> candidate.getTargetFeature().getId()))
                .distinct()
                .toList();

        Map<Long, List<FeatureRequirement>> requirements = groupByFeatureId(
                featureRequirementRepository.findAllByFeatureIdInOrderByDisplayOrderAscIdAsc(requirementOwnerIds),
                requirement -> requirement.getFeature().getId());
        Map<Long, List<FeatureIssue>> issues = groupByFeatureId(
                featureIssueRepository.findAllByFeatureIdIn(featureIds),
                issue -> issue.getFeature().getId());
        List<SplitFeatureSuggestion> suggestions =
                splitFeatureSuggestionRepository.findAllByFeatureIdInOrderByDisplayOrderAscIdAsc(featureIds);
        Map<Long, List<SplitFeatureSuggestion>> suggestionsByFeatureId = groupByFeatureId(
                suggestions, suggestion -> suggestion.getFeature().getId());
        Map<Long, List<FeatureRequirement>> suggestedRequirements =
                splitSuggestionFeatureRequirementRepository.findRequirementsBySuggestionId(
                        suggestions.stream().map(SplitFeatureSuggestion::getId).toList());

        Map<Long, List<FeatureReviewResponse.Feature>> bySectionId = new LinkedHashMap<>();
        Map<Long, FeatureSection> sectionsById = new LinkedHashMap<>();
        List<FeatureReviewResponse.Feature> unsectioned = new ArrayList<>();

        for (Feature feature : features) {
            FeatureReviewResponse.Feature mapped = FeatureReviewResponse.Feature.of(
                    feature,
                    toRequirements(requirements.get(feature.getId())),
                    issues.getOrDefault(feature.getId(), List.of()).stream()
                            .map(FeatureReviewResponse.Issue::from)
                            .toList(),
                    candidates.getOrDefault(feature.getId(), List.of()).stream()
                            .map(candidate -> {
                                Long targetId = candidate.getTargetFeature().getId();
                                return FeatureReviewResponse.DuplicateCandidate.of(
                                        candidate,
                                        featuresById.get(targetId),
                                        toRequirements(requirements.get(targetId)));
                            })
                            .toList(),
                    suggestionsByFeatureId.getOrDefault(feature.getId(), List.of()).stream()
                            .map(suggestion -> FeatureReviewResponse.SplitSuggestion.of(
                                    suggestion,
                                    toRequirements(suggestedRequirements.get(suggestion.getId()))))
                            .toList()
            );

            FeatureSection section = feature.getSection();

            if (section == null) {
                unsectioned.add(mapped);
                continue;
            }

            sectionsById.putIfAbsent(section.getId(), section);
            bySectionId.computeIfAbsent(section.getId(), id -> new ArrayList<>()).add(mapped);
        }

        // 분류는 분류끼리의 순서를 따른다. 기능이 등장하는 순서로 대신하면 병합·분리가 기능을
        // 다른 분류로 옮겼을 때 어긋난다 -- 옮겨 간 기능이 앞자리를 물려받으면 그 분류가 통째로
        // 목록 앞으로 끌려 올라가고, 맨 뒤에 놓으려고 만든 새 분류가 맨 앞에 뜬다.
        //
        // 기능은 이미 (displayOrder, id) 순으로 읽어 왔으므로 분류 안에서는 그대로 두면 된다.
        List<FeatureReviewResponse.SectionGroup> groups = sectionsById.values().stream()
                .sorted(Comparator.comparingInt(FeatureSection::getDisplayOrder)
                        .thenComparing(FeatureSection::getId))
                .map(section -> FeatureReviewResponse.SectionGroup.of(
                        section, bySectionId.get(section.getId())))
                .collect(Collectors.toCollection(ArrayList::new));

        if (!unsectioned.isEmpty()) {
            groups.add(FeatureReviewResponse.SectionGroup.of(null, unsectioned));
        }

        return groups;
    }

    private List<FeatureReviewResponse.Requirement> toRequirements(List<FeatureRequirement> requirements) {
        return requirements == null
                ? List.of()
                : requirements.stream().map(FeatureReviewResponse.Requirement::from).toList();
    }

    private <T> Map<Long, List<T>> groupByFeatureId(List<T> values, Function<T, Long> featureId) {
        return values.stream().collect(Collectors.groupingBy(
                featureId, LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * 문서 단위 작업의 권한 확인.
     *
     * <p>없는 문서, 남의 문서, 삭제된 프로젝트의 문서가 모두 같은 404가 되어야 문서 id의
     * 존재 여부가 새지 않는다.
     */
    private void requireSpecDocument(Long specDocumentId, Long userId) {
        specDocumentRepository.findOwned(specDocumentId, userId)
                .orElseThrow(() -> {
                    log.warn(
                            "[기능 검토] 기능명세서가 없거나 접근 권한이 없습니다. specDocumentId: {}, userId: {}",
                            specDocumentId,
                            userId
                    );
                    return new NotFoundException(ErrorCode.FEATURE_SPEC_NOT_ACCESSIBLE);
                });
    }
}
