package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult;
import com.github.galpiii.galpi.domain.featurespec.entity.DuplicateCandidate;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionFailureCode;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.Feature;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureIssue;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureIssueType;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureRequirement;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureSection;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.entity.SplitFeatureSuggestion;
import com.github.galpiii.galpi.domain.featurespec.entity.SplitSuggestionFeatureRequirement;
import com.github.galpiii.galpi.domain.featurespec.repository.DuplicateCandidateRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureIssueRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureRequirementRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureSectionRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.SplitFeatureSuggestionRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.SplitSuggestionFeatureRequirementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
class FeatureExtractionWriter {

    private final SpecDocumentRepository specDocumentRepository;
    private final FeatureSectionRepository featureSectionRepository;
    private final FeatureRepository featureRepository;
    private final FeatureRequirementRepository featureRequirementRepository;
    private final FeatureIssueRepository featureIssueRepository;
    private final DuplicateCandidateRepository duplicateCandidateRepository;
    private final SplitFeatureSuggestionRepository splitFeatureSuggestionRepository;
    private final SplitSuggestionFeatureRequirementRepository splitSuggestionFeatureRequirementRepository;

    @Transactional
    public void markProcessing(Long specDocumentId) {
        specDocument(specDocumentId).markProcessing();
    }

    @Transactional
    public void markFailed(Long specDocumentId, ExtractionFailureCode failureCode) {
        specDocument(specDocumentId).markFailed(failureCode);
    }

    /**
     * 죽은 프로세스가 남긴 분석을 실패로 정리한다.
     *
     * <p>작업 큐가 JVM 힙에 있어 프로세스가 죽으면 대기 중이던 작업이 사라진다. 분석에 쓸 임시
     * PDF도 함께 사라져 이어서 처리할 방법이 없으므로, 남은 문서는 실패로 표시해 사용자가
     * 다시 업로드하게 한다.
     *
     * <p>대상은 {@code staleBefore} 이전에 마지막으로 바뀐 문서뿐이다. 지금 다른 서버가 처리
     * 중인 문서를 죽이지 않으려면 상태가 아니라 나이로 판단해야 한다.
     */
    @Transactional
    public int failStale(OffsetDateTime staleBefore) {
        return specDocumentRepository.failStale(
                List.of(ExtractionStatus.PENDING, ExtractionStatus.PROCESSING),
                ExtractionStatus.FAILED,
                ExtractionFailureCode.ANALYSIS_FAILED,
                staleBefore,
                OffsetDateTime.now()
        );
    }

    @Transactional
    public void saveResult(Long specDocumentId, FeatureSpecExtractionResult result) {
        SpecDocument specDocument = specDocument(specDocumentId);

        Map<String, FeatureSection> sectionsByTitle = saveSections(specDocument, result.sections());
        List<Feature> savedFeatures = saveFeatures(specDocument, result.features(), sectionsByTitle);
        List<List<FeatureRequirement>> savedRequirements =
                saveRequirements(result.features(), savedFeatures);

        saveIssues(result.features(), savedFeatures);
        saveDuplicateCandidates(result.features(), savedFeatures);
        saveSplitSuggestions(result.features(), savedFeatures, savedRequirements);

        specDocument.markCompleted();
    }

    private SpecDocument specDocument(Long specDocumentId) {
        return specDocumentRepository.findById(specDocumentId)
                .orElseThrow(() -> new IllegalStateException(
                        "기능명세서를 찾을 수 없습니다. specDocumentId: " + specDocumentId));
    }

    private Map<String, FeatureSection> saveSections(
            SpecDocument specDocument,
            List<FeatureSpecExtractionResult.Section> sections
    ) {
        Map<String, FeatureSection> byTitle = new HashMap<>();

        for (int order = 0; order < sections.size(); order++) {
            FeatureSpecExtractionResult.Section section = sections.get(order);

            FeatureSection saved = featureSectionRepository.save(FeatureSection.builder()
                    .specDocument(specDocument)
                    .title(section.title())
                    .sourceTitle(section.sourceTitle())
                    .displayOrder(order)
                    .pageStart(section.pageStart())
                    .pageEnd(section.pageEnd())
                    .build());

            byTitle.put(section.title(), saved);
        }

        return byTitle;
    }

    private List<Feature> saveFeatures(
            SpecDocument specDocument,
            List<FeatureSpecExtractionResult.Feature> features,
            Map<String, FeatureSection> sectionsByTitle
    ) {
        List<Feature> saved = new ArrayList<>();

        for (int order = 0; order < features.size(); order++) {
            FeatureSpecExtractionResult.Feature feature = features.get(order);

            saved.add(featureRepository.save(Feature.builder()
                    .specDocument(specDocument)
                    .section(sectionsByTitle.get(feature.section()))
                    .name(feature.name())
                    .displayOrder(order)
                    .sourcePageStart(feature.source().pageStart())
                    .sourcePageEnd(feature.source().pageEnd())
                    .build()));
        }

        return saved;
    }

    /** 기능과 같은 순서로 각 기능의 요구사항 목록을 돌려준다. 분리 제안이 인덱스로 참조한다. */
    private List<List<FeatureRequirement>> saveRequirements(
            List<FeatureSpecExtractionResult.Feature> features,
            List<Feature> savedFeatures
    ) {
        List<List<FeatureRequirement>> savedByFeature = new ArrayList<>();

        for (int index = 0; index < features.size(); index++) {
            FeatureSpecExtractionResult.Feature feature = features.get(index);
            Feature savedFeature = savedFeatures.get(index);
            List<FeatureRequirement> saved = new ArrayList<>();

            for (int order = 0; order < feature.requirements().size(); order++) {
                FeatureSpecExtractionResult.Requirement requirement = feature.requirements().get(order);

                saved.add(featureRequirementRepository.save(FeatureRequirement.builder()
                        .feature(savedFeature)
                        .content(requirement.content())
                        .sourceText(requirement.originalText())
                        .displayOrder(order)
                        .build()));
            }

            savedByFeature.add(saved);
        }

        return savedByFeature;
    }

    private void saveIssues(
            List<FeatureSpecExtractionResult.Feature> features,
            List<Feature> savedFeatures
    ) {
        for (int index = 0; index < features.size(); index++) {
            FeatureSpecExtractionResult.Feature feature = features.get(index);
            Feature savedFeature = savedFeatures.get(index);

            for (FeatureSpecExtractionResult.Issue issue : feature.issues()) {
                featureIssueRepository.save(FeatureIssue.builder()
                        .feature(savedFeature)
                        .issueType(FeatureIssueType.valueOf(issue.type()))
                        .description(issue.description())
                        .build());
            }
        }
    }

    /**
     * 중복 후보만 extractionId를 쓴다.
     *
     * <p>다른 기능을 가리키는 참조라 이름으로 찾는 수밖에 없다. 정규화 계층이 중복된 id를 향한
     * 참조는 미리 버리므로, 여기 남은 targetExtractionId는 유일한 기능을 가리킨다.
     */
    private void saveDuplicateCandidates(
            List<FeatureSpecExtractionResult.Feature> features,
            List<Feature> savedFeatures
    ) {
        Map<String, Feature> featuresByExtractionId = new HashMap<>();

        for (int index = 0; index < features.size(); index++) {
            featuresByExtractionId.put(features.get(index).extractionId(), savedFeatures.get(index));
        }

        for (int index = 0; index < features.size(); index++) {
            FeatureSpecExtractionResult.Feature feature = features.get(index);
            Feature savedFeature = savedFeatures.get(index);

            for (FeatureSpecExtractionResult.DuplicateCandidate candidate : feature.duplicateCandidates()) {
                duplicateCandidateRepository.save(DuplicateCandidate.builder()
                        .feature(savedFeature)
                        .targetFeature(featuresByExtractionId.get(candidate.targetExtractionId()))
                        .reason(candidate.reason())
                        .suggestedMergedName(candidate.suggestedMergedName())
                        .suggestedSection(candidate.suggestedSection())
                        .build());
            }
        }
    }

    private void saveSplitSuggestions(
            List<FeatureSpecExtractionResult.Feature> features,
            List<Feature> savedFeatures,
            List<List<FeatureRequirement>> savedRequirements
    ) {
        for (int index = 0; index < features.size(); index++) {
            FeatureSpecExtractionResult.Feature feature = features.get(index);

            if (feature.splitSuggestion() == null) {
                continue;
            }

            Feature savedFeature = savedFeatures.get(index);
            List<FeatureRequirement> requirements = savedRequirements.get(index);
            List<FeatureSpecExtractionResult.SuggestedFeature> suggestedFeatures =
                    feature.splitSuggestion().suggestedFeatures();

            for (int order = 0; order < suggestedFeatures.size(); order++) {
                FeatureSpecExtractionResult.SuggestedFeature suggested = suggestedFeatures.get(order);

                SplitFeatureSuggestion savedSuggestion =
                        splitFeatureSuggestionRepository.save(SplitFeatureSuggestion.builder()
                                .feature(savedFeature)
                                .suggestedName(suggested.suggestedName())
                                .suggestedSection(suggested.suggestedSection())
                                .displayOrder(order)
                                .build());

                for (Integer requirementIndex : suggested.requirementIndexes()) {
                    splitSuggestionFeatureRequirementRepository.save(
                            SplitSuggestionFeatureRequirement.builder()
                                    .splitFeatureSuggestion(savedSuggestion)
                                    .featureRequirement(requirements.get(requirementIndex))
                                    .build());
                }
            }
        }
    }
}
