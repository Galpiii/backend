package com.github.galpiii.galpi.domain.featurespec.support;

import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.DuplicateCandidate;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Feature;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Issue;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Requirement;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Section;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.SplitSuggestion;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Source;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.SuggestedFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FeatureExtractionResultNormalizer — 추출 결과 검증과 복구")
class FeatureExtractionResultNormalizerTest {

    private static final long SPEC_DOCUMENT_ID = 1L;

    private final FeatureExtractionResultNormalizer normalizer = new FeatureExtractionResultNormalizer();

    private static Section section(String title) {
        return new Section(title, "3. " + title, 1, 2);
    }

    private static Requirement requirement(String content) {
        return new Requirement(content, "원문: " + content);
    }

    private static Feature feature(String extractionId, String section, List<Requirement> requirements) {
        return new Feature(extractionId, "기능 " + extractionId, section, requirements,
                new Source(1, 1), List.of(), List.of(), null);
    }

    private FeatureSpecExtractionResult normalize(List<Section> sections, List<Feature> features) {
        return normalizer.normalize(SPEC_DOCUMENT_ID, new FeatureSpecExtractionResult(sections, features));
    }

    @Nested
    @DisplayName("정상 입력")
    class Untouched {

        @Test
        @DisplayName("문제가 없으면 아무것도 바꾸지 않는다")
        void keepsValidResultAsIs() {
            Feature target = feature("f2", "회원", List.of(requirement("로그인한다.")));
            Feature source = new Feature("f1", "회원가입", "회원",
                    List.of(requirement("가입한다."), requirement("탈퇴한다.")),
                    new Source(1, 2),
                    List.of(new Issue("DUPLICATE_SUSPECTED", "겹친다."),
                            new Issue("SPLIT_RECOMMENDED", "나뉜다.")),
                    List.of(new DuplicateCandidate("f2", "같다.", "회원 관리", "회원")),
                    new SplitSuggestion(List.of(
                            new SuggestedFeature("가입", List.of(0), "회원"),
                            new SuggestedFeature("탈퇴", List.of(1), "회원"))));

            FeatureSpecExtractionResult result =
                    normalize(List.of(section("회원")), List.of(source, target));

            Feature normalized = result.features().getFirst();
            assertThat(normalized.section()).isEqualTo("회원");
            assertThat(normalized.issues()).hasSize(2);
            assertThat(normalized.duplicateCandidates()).hasSize(1);
            assertThat(normalized.splitSuggestion().suggestedFeatures()).hasSize(2);
        }

        @Test
        @DisplayName("어떤 복구를 하든 기능과 요구사항 수는 그대로다")
        void neverDropsFeaturesOrRequirements() {
            Feature broken = new Feature("f1", "기능", "없는 섹션",
                    List.of(requirement("하나"), requirement("둘")),
                    new Source(1, 1),
                    List.of(new Issue("UNKNOWN_TYPE", "?"),
                            new Issue("DUPLICATE_SUSPECTED", "겹친다.")),
                    List.of(new DuplicateCandidate("없는id", "?", "이름", "섹션")),
                    new SplitSuggestion(List.of(new SuggestedFeature("하나만", List.of(0), "섹션"))));

            FeatureSpecExtractionResult result = normalize(List.of(), List.of(broken));

            assertThat(result.features()).hasSize(1);
            assertThat(result.features().getFirst().requirements()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("섹션")
    class Sections {

        @Test
        @DisplayName("섹션명이 중복되면 먼저 온 것만 남긴다")
        void keepsFirstSectionOnDuplicateTitle() {
            Section first = new Section("회원", "3. 회원 및 인증", 3, 8);
            Section second = new Section("회원", "6. 관리자 기능", 19, 22);

            FeatureSpecExtractionResult result = normalize(List.of(first, second), List.of());

            assertThat(result.sections()).hasSize(1);
            assertThat(result.sections().getFirst().sourceTitle()).isEqualTo("3. 회원 및 인증");
        }

        @Test
        @DisplayName("섹션명이 255자를 넘으면 자른다")
        void truncatesSectionTitle() {
            FeatureSpecExtractionResult result =
                    normalize(List.of(section("가".repeat(300))), List.of());

            assertThat(result.sections().getFirst().title()).hasSize(255);
        }

        @Test
        @DisplayName("원문 섹션 제목은 자르지 않는다 — 원문 근거가 훼손된다")
        void keepsLongSourceTitle() {
            Section longSource = new Section("회원", "3. " + "가".repeat(400), 1, 2);

            FeatureSpecExtractionResult result = normalize(List.of(longSource), List.of());

            assertThat(result.sections().getFirst().sourceTitle()).hasSize(403);
        }
    }

    @Nested
    @DisplayName("기능")
    class Features {

        @Test
        @DisplayName("기능명이 255자를 넘으면 자른다")
        void truncatesFeatureName() {
            Feature longName = new Feature("f1", "가".repeat(300), null, List.of(),
                    new Source(1, 1), List.of(), List.of(), null);

            FeatureSpecExtractionResult result = normalize(List.of(), List.of(longName));

            assertThat(result.features().getFirst().name()).hasSize(255);
        }

        @Test
        @DisplayName("섹션을 찾을 수 없으면 미분류로 둔다")
        void clearsUnresolvableSection() {
            Feature orphan = feature("f1", "없는 섹션", List.of());

            FeatureSpecExtractionResult result = normalize(List.of(section("회원")), List.of(orphan));

            assertThat(result.features().getFirst().section()).isNull();
        }

        @Test
        @DisplayName("섹션이 null이면 그대로 둔다")
        void keepsNullSection() {
            FeatureSpecExtractionResult result =
                    normalize(List.of(section("회원")), List.of(feature("f1", null, List.of())));

            assertThat(result.features().getFirst().section()).isNull();
        }
    }

    @Nested
    @DisplayName("특이사항")
    class Issues {

        @Test
        @DisplayName("알 수 없는 유형은 버린다")
        void dropsUnknownIssueType() {
            Feature withUnknown = new Feature("f1", "기능", null, List.of(),
                    new Source(1, 1),
                    List.of(new Issue("UNKNOWN_TYPE", "?"),
                            new Issue("SOURCE_REVIEW_REQUIRED", "확인 필요")),
                    List.of(), null);

            FeatureSpecExtractionResult result = normalize(List.of(), List.of(withUnknown));

            assertThat(result.features().getFirst().issues())
                    .extracting(Issue::type)
                    .containsExactly("SOURCE_REVIEW_REQUIRED");
        }

        @Test
        @DisplayName("같은 유형이 두 번 오면 먼저 온 것만 남긴다")
        void dedupesIssueType() {
            Feature duplicated = new Feature("f1", "기능", null, List.of(),
                    new Source(1, 1),
                    List.of(new Issue("SOURCE_REVIEW_REQUIRED", "첫 번째"),
                            new Issue("SOURCE_REVIEW_REQUIRED", "두 번째")),
                    List.of(), null);

            FeatureSpecExtractionResult result = normalize(List.of(), List.of(duplicated));

            assertThat(result.features().getFirst().issues())
                    .extracting(Issue::description)
                    .containsExactly("첫 번째");
        }

        @Test
        @DisplayName("요구사항이 있는데 세부 요구사항 없음이 붙어 있으면 배지를 버린다")
        void dropsMissingRequirementsWhenRequirementsExist() {
            Feature contradicting = new Feature("f1", "기능", null, List.of(requirement("있다.")),
                    new Source(1, 1),
                    List.of(new Issue("MISSING_REQUIREMENTS", "없다고 한다.")),
                    List.of(), null);

            FeatureSpecExtractionResult result = normalize(List.of(), List.of(contradicting));

            assertThat(result.features().getFirst().issues()).isEmpty();
            assertThat(result.features().getFirst().requirements()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("중복 후보")
    class DuplicateCandidates {

        private Feature withCandidate(String targetExtractionId) {
            return new Feature("f1", "기능", null, List.of(),
                    new Source(1, 1),
                    List.of(new Issue("DUPLICATE_SUSPECTED", "겹친다.")),
                    List.of(new DuplicateCandidate(targetExtractionId, "같다.", "병합", "섹션")),
                    null);
        }

        @Test
        @DisplayName("없는 기능을 가리키면 버린다")
        void dropsUnknownTarget() {
            FeatureSpecExtractionResult result = normalize(List.of(), List.of(withCandidate("없는id")));

            assertThat(result.features().getFirst().duplicateCandidates()).isEmpty();
        }

        @Test
        @DisplayName("자기 자신을 가리키면 버린다")
        void dropsSelfReference() {
            FeatureSpecExtractionResult result = normalize(List.of(), List.of(withCandidate("f1")));

            assertThat(result.features().getFirst().duplicateCandidates()).isEmpty();
        }

        @Test
        @DisplayName("extractionId가 중복되면 그 id를 향한 참조만 버리고 기능은 남긴다")
        void dropsReferenceToAmbiguousId() {
            Feature twinA = feature("dup", null, List.of());
            Feature twinB = feature("dup", null, List.of());

            FeatureSpecExtractionResult result =
                    normalize(List.of(), List.of(withCandidate("dup"), twinA, twinB));

            assertThat(result.features()).hasSize(3);
            assertThat(result.features().getFirst().duplicateCandidates()).isEmpty();
        }

        @Test
        @DisplayName("병합 제안 이름이 255자를 넘으면 자른다")
        void truncatesSuggestedNames() {
            Feature target = feature("f2", null, List.of());
            Feature source = new Feature("f1", "기능", null, List.of(),
                    new Source(1, 1),
                    List.of(new Issue("DUPLICATE_SUSPECTED", "겹친다.")),
                    List.of(new DuplicateCandidate("f2", "같다.", "가".repeat(300), "나".repeat(300))),
                    null);

            FeatureSpecExtractionResult result = normalize(List.of(), List.of(source, target));

            DuplicateCandidate candidate = result.features().getFirst().duplicateCandidates().getFirst();
            assertThat(candidate.suggestedMergedName()).hasSize(255);
            assertThat(candidate.suggestedSection()).hasSize(255);
        }
    }

    @Nested
    @DisplayName("분리 제안")
    class SplitSuggestions {

        private Feature withSplit(List<Requirement> requirements, List<SuggestedFeature> suggested) {
            return new Feature("f1", "기능", null, requirements,
                    new Source(1, 1),
                    List.of(new Issue("SPLIT_RECOMMENDED", "나뉜다.")),
                    List.of(),
                    new SplitSuggestion(suggested));
        }

        @Test
        @DisplayName("요구사항을 하나도 빠짐없이 한 번씩 나눠 가지면 유지한다")
        void keepsValidSplit() {
            Feature valid = withSplit(
                    List.of(requirement("하나"), requirement("둘"), requirement("셋")),
                    List.of(new SuggestedFeature("A", List.of(0, 2), "섹션"),
                            new SuggestedFeature("B", List.of(1), "섹션")));

            FeatureSpecExtractionResult result = normalize(List.of(), List.of(valid));

            assertThat(result.features().getFirst().splitSuggestion().suggestedFeatures()).hasSize(2);
        }

        @Test
        @DisplayName("후보가 하나뿐이면 버린다")
        void dropsSingleSuggestedFeature() {
            Feature single = withSplit(
                    List.of(requirement("하나")),
                    List.of(new SuggestedFeature("A", List.of(0), "섹션")));

            FeatureSpecExtractionResult result = normalize(List.of(), List.of(single));

            assertThat(result.features().getFirst().splitSuggestion()).isNull();
        }

        @Test
        @DisplayName("범위를 벗어난 index가 있으면 버린다")
        void dropsOutOfRangeIndex() {
            Feature outOfRange = withSplit(
                    List.of(requirement("하나"), requirement("둘")),
                    List.of(new SuggestedFeature("A", List.of(0), "섹션"),
                            new SuggestedFeature("B", List.of(5), "섹션")));

            FeatureSpecExtractionResult result = normalize(List.of(), List.of(outOfRange));

            assertThat(result.features().getFirst().splitSuggestion()).isNull();
        }

        @Test
        @DisplayName("같은 요구사항이 두 곳에 배정되면 버린다")
        void dropsDuplicatedIndex() {
            Feature duplicated = withSplit(
                    List.of(requirement("하나"), requirement("둘")),
                    List.of(new SuggestedFeature("A", List.of(0), "섹션"),
                            new SuggestedFeature("B", List.of(0), "섹션")));

            FeatureSpecExtractionResult result = normalize(List.of(), List.of(duplicated));

            assertThat(result.features().getFirst().splitSuggestion()).isNull();
        }

        @Test
        @DisplayName("배정되지 않은 요구사항이 남으면 버린다")
        void dropsWhenRequirementIsUnassigned() {
            Feature missing = withSplit(
                    List.of(requirement("하나"), requirement("둘"), requirement("셋")),
                    List.of(new SuggestedFeature("A", List.of(0), "섹션"),
                            new SuggestedFeature("B", List.of(1), "섹션")));

            FeatureSpecExtractionResult result = normalize(List.of(), List.of(missing));

            assertThat(result.features().getFirst().splitSuggestion()).isNull();
        }
    }

    @Nested
    @DisplayName("특이사항과 데이터의 짝")
    class IssueDataConsistency {

        @Test
        @DisplayName("중복 후보가 없는데 배지만 있으면 배지를 버린다")
        void dropsDuplicateIssueWithoutCandidates() {
            Feature orphanIssue = new Feature("f1", "기능", null, List.of(),
                    new Source(1, 1),
                    List.of(new Issue("DUPLICATE_SUSPECTED", "겹친다.")),
                    List.of(), null);

            FeatureSpecExtractionResult result = normalize(List.of(), List.of(orphanIssue));

            assertThat(result.features().getFirst().issues()).isEmpty();
        }

        @Test
        @DisplayName("배지가 없는데 중복 후보만 있으면 후보를 버린다")
        void dropsCandidatesWithoutIssue() {
            Feature target = feature("f2", null, List.of());
            Feature orphanData = new Feature("f1", "기능", null, List.of(),
                    new Source(1, 1),
                    List.of(),
                    List.of(new DuplicateCandidate("f2", "같다.", "병합", "섹션")),
                    null);

            FeatureSpecExtractionResult result = normalize(List.of(), List.of(orphanData, target));

            assertThat(result.features().getFirst().duplicateCandidates()).isEmpty();
        }

        @Test
        @DisplayName("분리 제안이 없는데 배지만 있으면 배지를 버린다")
        void dropsSplitIssueWithoutSuggestion() {
            Feature orphanIssue = new Feature("f1", "기능", null, List.of(requirement("하나")),
                    new Source(1, 1),
                    List.of(new Issue("SPLIT_RECOMMENDED", "나뉜다.")),
                    List.of(), null);

            FeatureSpecExtractionResult result = normalize(List.of(), List.of(orphanIssue));

            assertThat(result.features().getFirst().issues()).isEmpty();
        }

        @Test
        @DisplayName("배지가 없는데 분리 제안만 있으면 제안을 버린다")
        void dropsSuggestionWithoutIssue() {
            Feature orphanData = new Feature("f1", "기능", null,
                    List.of(requirement("하나"), requirement("둘")),
                    new Source(1, 1),
                    List.of(),
                    List.of(),
                    new SplitSuggestion(List.of(
                            new SuggestedFeature("A", List.of(0), "섹션"),
                            new SuggestedFeature("B", List.of(1), "섹션"))));

            FeatureSpecExtractionResult result = normalize(List.of(), List.of(orphanData));

            assertThat(result.features().getFirst().splitSuggestion()).isNull();
        }

        @Test
        @DisplayName("배정이 어긋나 분리 제안이 버려지면 배지도 함께 사라진다")
        void dropsSplitIssueWhenSuggestionIsInvalid() {
            Feature invalid = new Feature("f1", "기능", null, List.of(requirement("하나")),
                    new Source(1, 1),
                    List.of(new Issue("SPLIT_RECOMMENDED", "나뉜다.")),
                    List.of(),
                    new SplitSuggestion(List.of(new SuggestedFeature("A", List.of(0), "섹션"))));

            FeatureSpecExtractionResult result = normalize(List.of(), List.of(invalid));

            assertThat(result.features().getFirst().splitSuggestion()).isNull();
            assertThat(result.features().getFirst().issues()).isEmpty();
        }
    }
}
