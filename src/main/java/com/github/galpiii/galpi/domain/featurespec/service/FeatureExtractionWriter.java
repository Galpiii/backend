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
     * 아직 끝나지 않은 분석을 모두 실패로 정리한다.
     *
     * <p>작업 큐가 JVM 힙에 있어 프로세스가 죽으면 대기 중이던 작업이 사라진다. 분석에 쓸 임시
     * PDF도 함께 사라져 이어서 처리할 방법이 없으므로, 남은 문서는 실패로 표시해 사용자가
     * 다시 업로드하게 한다.
     */
    @Transactional
    public int failAllInProgress() {
        return specDocumentRepository.failAll(
                List.of(ExtractionStatus.PENDING, ExtractionStatus.PROCESSING),
                ExtractionStatus.FAILED,
                ExtractionFailureCode.ANALYSIS_FAILED
        );
    }

    @Transactional
    public void saveResult(Long specDocumentId, FeatureSpecExtractionResult result) {
        SpecDocument specDocument = specDocument(specDocumentId);

        Map<String, FeatureSection> sectionsByTitle = saveSections(specDocument, result.sections());
        Map<String, Feature> featuresByExtractionId = saveFeatures(specDocument, result.features(), sectionsByTitle);
        Map<String, List<FeatureRequirement>> requirementsByExtractionId =
                saveRequirements(result.features(), featuresByExtractionId);

        saveIssues(result.features(), featuresByExtractionId);
        saveDuplicateCandidates(result.features(), featuresByExtractionId);
        saveSplitSuggestions(result.features(), featuresByExtractionId, requirementsByExtractionId);

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

    private Map<String, Feature> saveFeatures(
            SpecDocument specDocument,
            List<FeatureSpecExtractionResult.Feature> features,
            Map<String, FeatureSection> sectionsByTitle
    ) {
        Map<String, Feature> byExtractionId = new HashMap<>();

        for (int order = 0; order < features.size(); order++) {
            FeatureSpecExtractionResult.Feature feature = features.get(order);

            Feature saved = featureRepository.save(Feature.builder()
                    .specDocument(specDocument)
                    .section(sectionsByTitle.get(feature.section()))
                    .name(feature.name())
                    .displayOrder(order)
                    .sourcePageStart(feature.source().pageStart())
                    .sourcePageEnd(feature.source().pageEnd())
                    .build());

            byExtractionId.put(feature.extractionId(), saved);
        }

        return byExtractionId;
    }

    private Map<String, List<FeatureRequirement>> saveRequirements(
            List<FeatureSpecExtractionResult.Feature> features,
            Map<String, Feature> featuresByExtractionId
    ) {
        Map<String, List<FeatureRequirement>> byExtractionId = new HashMap<>();

        for (FeatureSpecExtractionResult.Feature feature : features) {
            Feature savedFeature = featuresByExtractionId.get(feature.extractionId());
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

            byExtractionId.put(feature.extractionId(), saved);
        }

        return byExtractionId;
    }

    private void saveIssues(
            List<FeatureSpecExtractionResult.Feature> features,
            Map<String, Feature> featuresByExtractionId
    ) {
        for (FeatureSpecExtractionResult.Feature feature : features) {
            Feature savedFeature = featuresByExtractionId.get(feature.extractionId());

            for (FeatureSpecExtractionResult.Issue issue : feature.issues()) {
                featureIssueRepository.save(FeatureIssue.builder()
                        .feature(savedFeature)
                        .issueType(FeatureIssueType.valueOf(issue.type()))
                        .description(issue.description())
                        .build());
            }
        }
    }

    private void saveDuplicateCandidates(
            List<FeatureSpecExtractionResult.Feature> features,
            Map<String, Feature> featuresByExtractionId
    ) {
        for (FeatureSpecExtractionResult.Feature feature : features) {
            Feature savedFeature = featuresByExtractionId.get(feature.extractionId());

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
            Map<String, Feature> featuresByExtractionId,
            Map<String, List<FeatureRequirement>> requirementsByExtractionId
    ) {
        for (FeatureSpecExtractionResult.Feature feature : features) {
            if (feature.splitSuggestion() == null) {
                continue;
            }

            Feature savedFeature = featuresByExtractionId.get(feature.extractionId());
            List<FeatureRequirement> requirements = requirementsByExtractionId.get(feature.extractionId());
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
