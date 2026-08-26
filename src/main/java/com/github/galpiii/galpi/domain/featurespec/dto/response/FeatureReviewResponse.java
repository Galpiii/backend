package com.github.galpiii.galpi.domain.featurespec.dto.response;

import com.github.galpiii.galpi.domain.featurespec.entity.FeatureIssue;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureIssueType;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureRequirement;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureSection;
import com.github.galpiii.galpi.domain.featurespec.entity.SplitFeatureSuggestion;

import java.util.List;

/**
 * 검토 화면에 뿌릴 기능 목록. 원문의 분류대로 묶어서 준다.
 *
 * <p>페이지를 나누지 않는다. 업로드가 100페이지 PDF까지만 받으므로 기능 수가 구조적으로
 * 묶여 있고, 분류로 묶어 보여 주는 화면에서 페이지 경계가 분류를 반으로 자르면 같은 분류
 * 제목이 두 페이지에 나온다.
 *
 * <p>어느 분류에도 속하지 않는 기능은 {@code sectionId}가 비어 있는 묶음으로 맨 뒤에 온다.
 */
public record FeatureReviewResponse(List<SectionGroup> sections) {

    /**
     * @param sectionId 미분류 묶음이면 비어 있다
     */
    public record SectionGroup(Long sectionId, String title, List<Feature> features) {

        public static SectionGroup of(FeatureSection section, List<Feature> features) {
            return section == null
                    ? new SectionGroup(null, null, features)
                    : new SectionGroup(section.getId(), section.getTitle(), features);
        }
    }

    public record Feature(
            Long featureId,
            String name,
            FeatureReviewStatus reviewStatus,
            Integer sourcePageStart,
            Integer sourcePageEnd,
            List<Requirement> requirements,
            List<Issue> issues,
            List<DuplicateCandidate> duplicateCandidates,
            List<SplitSuggestion> splitSuggestions
    ) {

        public static Feature of(
                com.github.galpiii.galpi.domain.featurespec.entity.Feature feature,
                List<Requirement> requirements,
                List<Issue> issues,
                List<DuplicateCandidate> duplicateCandidates,
                List<SplitSuggestion> splitSuggestions
        ) {
            return new Feature(
                    feature.getId(),
                    feature.getName(),
                    feature.getReviewStatus(),
                    feature.getSourcePageStart(),
                    feature.getSourcePageEnd(),
                    requirements,
                    issues,
                    duplicateCandidates,
                    splitSuggestions
            );
        }
    }

    /**
     * @param sourceText 사용자가 검토 중에 직접 추가한 요구사항이면 비어 있다
     */
    public record Requirement(Long requirementId, String content, String sourceText) {

        public static Requirement from(FeatureRequirement requirement) {
            return new Requirement(
                    requirement.getId(),
                    requirement.getContent(),
                    requirement.getSourceText()
            );
        }
    }

    public record Issue(FeatureIssueType issueType, String description) {

        public static Issue from(FeatureIssue issue) {
            return new Issue(issue.getIssueType(), issue.getDescription());
        }
    }

    /**
     * 합칠 상대와 비교에 필요한 정보를 함께 준다.
     *
     * <p>같은 중복 관계는 한 방향으로만 저장되므로 배지도 한쪽에만 붙는다. 그래서 "확인 필요"
     * 목록에는 지목한 기능만 들어오고 상대는 빠진다 — 상대의 요구사항을 여기 실어 보내지
     * 않으면 화면이 두 기능을 나란히 놓고 비교시킬 수가 없다.
     *
     * @param targetRequirements 상대 기능의 요구사항. {@code filter=ALL}이면 상대 기능 자체도
     *                           목록에 있어 같은 내용이 두 번 실리지만, 문서당 중복 후보가
     *                           많아야 몇 건이라 그 편이 낫다
     */
    public record DuplicateCandidate(
            Long targetFeatureId,
            String targetFeatureName,
            Integer targetSourcePageStart,
            Integer targetSourcePageEnd,
            List<Requirement> targetRequirements,
            String reason,
            String suggestedMergedName,
            String suggestedSection
    ) {

        public static DuplicateCandidate of(
                com.github.galpiii.galpi.domain.featurespec.entity.DuplicateCandidate candidate,
                com.github.galpiii.galpi.domain.featurespec.entity.Feature targetFeature,
                List<Requirement> targetRequirements
        ) {
            return new DuplicateCandidate(
                    targetFeature.getId(),
                    targetFeature.getName(),
                    targetFeature.getSourcePageStart(),
                    targetFeature.getSourcePageEnd(),
                    targetRequirements,
                    candidate.getReason(),
                    candidate.getSuggestedMergedName(),
                    candidate.getSuggestedSection()
            );
        }
    }

    /**
     * @param requirements 이 추천 기능이 가져갈 요구사항. 분리를 확정할 때 보내는 것은
     *                     {@code suggestionId}와 이름뿐이고, 이 목록은 미리보기용이다
     */
    public record SplitSuggestion(
            Long suggestionId,
            String suggestedName,
            String suggestedSection,
            List<Requirement> requirements
    ) {

        public static SplitSuggestion of(SplitFeatureSuggestion suggestion, List<Requirement> requirements) {
            return new SplitSuggestion(
                    suggestion.getId(),
                    suggestion.getSuggestedName(),
                    suggestion.getSuggestedSection(),
                    requirements
            );
        }
    }
}
