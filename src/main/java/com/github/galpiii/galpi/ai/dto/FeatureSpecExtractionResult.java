package com.github.galpiii.galpi.ai.dto;

import java.util.List;

// 기능명세서 분석 응답
public record FeatureSpecExtractionResult(
        List<Section> sections,
        List<Feature> features
) {

    public record Section(
            String title,
            String sourceTitle,
            Integer pageStart,
            Integer pageEnd
    ) {
    }

    public record Feature(
            String extractionId,
            String name,
            String section,
            List<Requirement> requirements,
            Source source,
            List<Issue> issues,
            List<DuplicateCandidate> duplicateCandidates,
            SplitSuggestion splitSuggestion
    ) {
    }

    public record Requirement(
            String content,
            String originalText
    ) {
    }

    public record Source(
            Integer pageStart,
            Integer pageEnd
    ) {
    }

    public record Issue(
            String type,
            String description
    ) {
    }

    public record DuplicateCandidate(
            String targetExtractionId,
            String reason,
            String suggestedMergedName,
            String suggestedSection
    ) {
    }

    public record SplitSuggestion(
            List<SuggestedFeature> suggestedFeatures
    ) {
    }

    public record SuggestedFeature(
            String suggestedName,
            List<Integer> requirementIndexes,
            String suggestedSection
    ) {
    }
}
