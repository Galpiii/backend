package com.github.galpiii.galpi.ai.dto;

import java.util.List;

/**
 * 섹션을 먼저 직렬화하여 PR마다 바뀌지 않는 입력 접두사를 유지한다.
 */
public record FeatureMatchingRequest(List<Section> sections, PullRequest pullRequest) {
    public record Section(Long sectionId, String title, List<Feature> features) {
    }

    public record Feature(Long featureId, String name, List<Requirement> requirements) {
    }

    public record Requirement(Long requirementId, String content) {
    }

    public record PullRequest(String repositoryName, String title, String body,
                              String analysisSummary, String changeType, List<String> commitMessages,
                              List<ChangedFile> changedFiles) {
    }

    public record ChangedFile(String path, String status, int additions, int deletions) {
    }
}
