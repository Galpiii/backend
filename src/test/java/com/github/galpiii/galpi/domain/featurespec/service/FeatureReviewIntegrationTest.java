package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.dto.FeatureReviewFilter;
import com.github.galpiii.galpi.domain.featurespec.dto.request.FeatureMergeRequest;
import com.github.galpiii.galpi.domain.featurespec.dto.request.FeatureSplitRequest;
import com.github.galpiii.galpi.domain.featurespec.dto.request.FeatureUpdateRequest;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureReviewResponse;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureReviewSummaryResponse;
import com.github.galpiii.galpi.domain.featurespec.entity.DuplicateCandidate;
import com.github.galpiii.galpi.domain.featurespec.entity.Feature;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureIssue;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureIssueType;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureRequirement;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;
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
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("기능 검토 — 실제 Postgres")
class FeatureReviewIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private FeatureReviewService featureReviewService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private SpecDocumentRepository specDocumentRepository;
    @Autowired
    private FeatureSectionRepository featureSectionRepository;
    @Autowired
    private FeatureRepository featureRepository;
    @Autowired
    private FeatureRequirementRepository featureRequirementRepository;
    @Autowired
    private FeatureIssueRepository featureIssueRepository;
    @Autowired
    private DuplicateCandidateRepository duplicateCandidateRepository;
    @Autowired
    private SplitFeatureSuggestionRepository splitFeatureSuggestionRepository;
    @Autowired
    private SplitSuggestionFeatureRequirementRepository splitSuggestionFeatureRequirementRepository;

    private Long userId;
    private Long projectId;
    private Long specDocumentId;
    private SpecDocument specDocument;
    private FeatureSection section;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(
                User.ofGithub(System.nanoTime(), "galpi-tester", "테스터", null, null));
        Project project = projectRepository.save(Project.create(user, "갈피"));
        specDocument = specDocumentRepository.save(SpecDocument.builder()
                .project(project).user(user).fileName("기능명세서.pdf").build());
        section = featureSectionRepository.save(FeatureSection.builder()
                .specDocument(specDocument).title("게시글").sourceTitle("3. 게시글")
                .displayOrder(0).pageStart(3).pageEnd(5).build());

        userId = user.getId();
        projectId = project.getId();
        specDocumentId = specDocument.getId();
    }

    @Nested
    @DisplayName("목록 조회")
    class ListFeatures {

        @Test
        @DisplayName("분류별로 묶고 미분류는 맨 뒤에 둔다")
        void groupsBySectionWithUnsectionedLast() {
            newFeature("게시글 작성", section, 0);
            newFeature("분류 없는 기능", null, 1);

            FeatureReviewResponse response = list(FeatureReviewFilter.ALL);

            assertThat(response.sections()).hasSize(2);
            assertThat(response.sections().getFirst().sectionId()).isEqualTo(section.getId());
            assertThat(response.sections().getFirst().title()).isEqualTo("게시글");
            assertThat(response.sections().getLast().sectionId()).isNull();
            assertThat(response.sections().getLast().features())
                    .extracting(FeatureReviewResponse.Feature::name)
                    .containsExactly("분류 없는 기능");
        }

        @Test
        @DisplayName("분류는 분류 순서를, 기능은 분류 안에서 자기 순서를 따른다")
        void ordersSectionsAndFeaturesIndependently() {
            FeatureSection later = featureSectionRepository.save(FeatureSection.builder()
                    .specDocument(specDocument).title("커뮤니티").displayOrder(1).build());

            // 뒤 분류에 앞자리 기능을, 앞 분류에 뒷자리 기능을 넣는다. 병합이 기능을 다른
            // 분류로 옮기면 실제로 이런 모양이 된다.
            newFeature("맨 앞 기능", later, 0);
            newFeature("맨 뒤 기능", section, 9);
            newFeature("중간 기능", later, 5);

            List<FeatureReviewResponse.SectionGroup> sections = list(FeatureReviewFilter.ALL).sections();

            // 기능 등장 순서를 따랐다면 "커뮤니티"가 먼저 나온다.
            assertThat(sections).extracting(FeatureReviewResponse.SectionGroup::title)
                    .containsExactly("게시글", "커뮤니티");
            assertThat(sections.getLast().features())
                    .extracting(FeatureReviewResponse.Feature::name)
                    .containsExactly("맨 앞 기능", "중간 기능");
        }

        @Test
        @DisplayName("검토로 새로 만든 분류는 기존 분류 뒤에 온다")
        void placesSectionCreatedByReviewLast() {
            Feature source = newFeature("게시글 작성", section, 0);
            Feature target = newFeature("글쓰기", section, 1);
            newFeature("남는 기능", section, 2);
            newCandidate(source, target, "게시글 관리", "커뮤니티");

            // 합쳐진 기능은 displayOrder 0을 물려받는다. 기능 순서로 분류를 정렬하면 새로 만든
            // 분류가 맨 앞으로 올라간다.
            featureReviewService.merge(specDocumentId, userId, source.getId(),
                    new FeatureMergeRequest(target.getId(), "게시글 관리"));

            assertThat(list(FeatureReviewFilter.ALL).sections())
                    .extracting(FeatureReviewResponse.SectionGroup::title)
                    .containsExactly("게시글", "커뮤니티");
        }

        @Test
        @DisplayName("확인 필요 필터는 특이사항이 있는 기능만 준다")
        void filtersReviewRequired() {
            Feature withIssue = newFeature("게시글 작성", section, 0);
            newFeature("댓글 작성", section, 1);
            newIssue(withIssue, FeatureIssueType.MISSING_REQUIREMENTS);

            assertThat(featureNames(list(FeatureReviewFilter.REVIEW_REQUIRED)))
                    .containsExactly("게시글 작성");
            assertThat(featureNames(list(FeatureReviewFilter.NO_ISSUE)))
                    .containsExactly("댓글 작성");
        }

        @Test
        @DisplayName("검토 완료 필터는 승인과 수정을 함께 준다")
        void filtersReviewed() {
            Feature confirmed = newFeature("게시글 작성", section, 0);
            Feature modified = newFeature("댓글 작성", section, 1);
            newFeature("좋아요", section, 2);

            featureReviewService.confirm(specDocumentId, userId, confirmed.getId());
            featureReviewService.update(specDocumentId, userId, modified.getId(),
                    new FeatureUpdateRequest("댓글 관리", null));

            assertThat(featureNames(list(FeatureReviewFilter.REVIEWED)))
                    .containsExactly("게시글 작성", "댓글 관리");
        }

        @Test
        @DisplayName("중복 후보에 상대 기능명을 함께 담는다")
        void includesTargetFeatureName() {
            Feature source = newFeature("게시글 작성", section, 0);
            Feature target = newFeature("글쓰기", section, 1);
            newIssue(source, FeatureIssueType.DUPLICATE_SUSPECTED);
            newCandidate(source, target, "게시글 관리", "게시글");

            FeatureReviewResponse.Feature mapped = list(FeatureReviewFilter.ALL)
                    .sections().getFirst().features().getFirst();

            assertThat(mapped.duplicateCandidates()).singleElement()
                    .satisfies(candidate -> {
                        assertThat(candidate.targetFeatureId()).isEqualTo(target.getId());
                        assertThat(candidate.targetFeatureName()).isEqualTo("글쓰기");
                        assertThat(candidate.suggestedMergedName()).isEqualTo("게시글 관리");
                        assertThat(candidate.suggestedSection()).isEqualTo("게시글");
                    });
        }

        @Test
        @DisplayName("필터에서 걸러진 기능이 중복 상대여도 비교에 필요한 정보를 모두 찾아낸다")
        void resolvesTargetOutsideFilter() {
            Feature source = newFeature("게시글 작성", section, 0);
            Feature target = newFeature("글쓰기", section, 1, 7, 9);
            newRequirement(target, "글을 쓴다.", 0);
            newRequirement(target, "글을 지운다.", 1);
            newIssue(source, FeatureIssueType.DUPLICATE_SUSPECTED);
            newCandidate(source, target, "게시글 관리", "커뮤니티");

            // target은 특이사항이 없어 REVIEW_REQUIRED 결과에 포함되지 않는다. 그래도 화면이
            // 두 기능을 나란히 비교시켜야 하므로 상대 정보가 후보 안에 실려 나가야 한다.
            FeatureReviewResponse.Feature mapped = list(FeatureReviewFilter.REVIEW_REQUIRED)
                    .sections().getFirst().features().getFirst();

            assertThat(mapped.duplicateCandidates()).singleElement().satisfies(candidate -> {
                assertThat(candidate.targetFeatureId()).isEqualTo(target.getId());
                assertThat(candidate.targetFeatureName()).isEqualTo("글쓰기");
                assertThat(candidate.targetSourcePageStart()).isEqualTo(7);
                assertThat(candidate.targetSourcePageEnd()).isEqualTo(9);
                assertThat(candidate.targetRequirements())
                        .extracting(FeatureReviewResponse.Requirement::content)
                        .containsExactly("글을 쓴다.", "글을 지운다.");
                assertThat(candidate.suggestedMergedName()).isEqualTo("게시글 관리");
                assertThat(candidate.suggestedSection()).isEqualTo("커뮤니티");
            });
        }

        @Test
        @DisplayName("분리 추천안에 가져갈 요구사항 내용을 담는다")
        void includesSplitSuggestionRequirements() {
            Feature feature = newFeature("게시글", section, 0);
            FeatureRequirement first = newRequirement(feature, "게시글을 작성한다.", 0);
            FeatureRequirement second = newRequirement(feature, "댓글을 작성한다.", 1);
            newIssue(feature, FeatureIssueType.SPLIT_RECOMMENDED);
            newSuggestion(feature, "게시글 관리", "게시글", 0, first);
            newSuggestion(feature, "댓글 관리", "댓글", 1, second);

            FeatureReviewResponse.Feature mapped = list(FeatureReviewFilter.ALL)
                    .sections().getFirst().features().getFirst();

            assertThat(mapped.splitSuggestions())
                    .extracting(FeatureReviewResponse.SplitSuggestion::suggestedName,
                            FeatureReviewResponse.SplitSuggestion::suggestedSection)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple("게시글 관리", "게시글"),
                            org.assertj.core.api.Assertions.tuple("댓글 관리", "댓글"));
            assertThat(mapped.splitSuggestions().getFirst().requirements())
                    .extracting(FeatureReviewResponse.Requirement::requirementId,
                            FeatureReviewResponse.Requirement::content)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple(first.getId(), "게시글을 작성한다."));
        }

        @Test
        @DisplayName("남의 프로젝트는 404")
        void rejectsOtherOwner() {
            User stranger = userRepository.save(
                    User.ofGithub(System.nanoTime(), "stranger", "남", null, null));

            assertThatThrownBy(() -> featureReviewService.list(
                    specDocumentId, stranger.getId(), FeatureReviewFilter.ALL))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("기능 수정")
    class Update {

        @Test
        @DisplayName("요구사항 목록을 보낸 그대로 맞춘다")
        void replacesRequirements() {
            Feature feature = newFeature("게시글", section, 0);
            FeatureRequirement kept = newRequirement(feature, "게시글을 작성한다.", 0);
            newRequirement(feature, "지워질 요구사항", 1);

            featureReviewService.update(specDocumentId, userId, feature.getId(),
                    new FeatureUpdateRequest(null, List.of(
                            new FeatureUpdateRequest.Requirement(kept.getId(), "게시글을 등록한다."),
                            new FeatureUpdateRequest.Requirement(null, "게시글을 고정한다."))));

            List<FeatureRequirement> saved =
                    featureRequirementRepository.findAllByFeatureIdOrderByDisplayOrderAscIdAsc(feature.getId());

            assertThat(saved).extracting(FeatureRequirement::getContent)
                    .containsExactly("게시글을 등록한다.", "게시글을 고정한다.");
            assertThat(saved).extracting(FeatureRequirement::getDisplayOrder).containsExactly(0, 1);
        }

        @Test
        @DisplayName("기존 요구사항의 원문 근거는 내용을 고쳐도 그대로 둔다")
        void keepsSourceTextOnEdit() {
            Feature feature = newFeature("게시글", section, 0);
            FeatureRequirement requirement = newRequirement(feature, "게시글을 작성한다.", 0);

            featureReviewService.update(specDocumentId, userId, feature.getId(),
                    new FeatureUpdateRequest(null, List.of(
                            new FeatureUpdateRequest.Requirement(requirement.getId(), "게시글을 등록한다."))));

            assertThat(featureRequirementRepository.findById(requirement.getId()).orElseThrow())
                    .satisfies(saved -> {
                        assertThat(saved.getContent()).isEqualTo("게시글을 등록한다.");
                        assertThat(saved.getSourceText()).isEqualTo("원문: 게시글을 작성한다.");
                    });
        }

        @Test
        @DisplayName("사용자가 추가한 요구사항은 원문 근거가 비어 있다")
        void addedRequirementHasNoSourceText() {
            Feature feature = newFeature("게시글", section, 0);

            featureReviewService.update(specDocumentId, userId, feature.getId(),
                    new FeatureUpdateRequest(null, List.of(
                            new FeatureUpdateRequest.Requirement(null, "게시글을 고정한다."))));

            assertThat(featureRequirementRepository
                    .findAllByFeatureIdOrderByDisplayOrderAscIdAsc(feature.getId()).getFirst().getSourceText())
                    .isNull();
        }

        @Test
        @DisplayName("빈 배열이면 요구사항을 전부 지우고, 필드가 없으면 건드리지 않는다")
        void distinguishesEmptyListFromAbsentField() {
            Feature untouched = newFeature("댓글", section, 0);
            newRequirement(untouched, "댓글을 작성한다.", 0);
            Feature cleared = newFeature("게시글", section, 1);
            newRequirement(cleared, "게시글을 작성한다.", 0);

            featureReviewService.update(specDocumentId, userId, untouched.getId(),
                    new FeatureUpdateRequest("댓글 관리", null));
            featureReviewService.update(specDocumentId, userId, cleared.getId(),
                    new FeatureUpdateRequest(null, List.of()));

            assertThat(featureRequirementRepository
                    .findAllByFeatureIdOrderByDisplayOrderAscIdAsc(untouched.getId())).hasSize(1);
            assertThat(featureRequirementRepository
                    .findAllByFeatureIdOrderByDisplayOrderAscIdAsc(cleared.getId())).isEmpty();
        }

        @Test
        @DisplayName("다른 기능의 요구사항 id를 보내면 거절한다")
        void rejectsRequirementOfAnotherFeature() {
            Feature feature = newFeature("게시글", section, 0);
            Feature other = newFeature("댓글", section, 1);
            FeatureRequirement stranger = newRequirement(other, "댓글을 작성한다.", 0);

            assertThatThrownBy(() -> featureReviewService.update(
                    specDocumentId, userId, feature.getId(),
                    new FeatureUpdateRequest(null, List.of(
                            new FeatureUpdateRequest.Requirement(stranger.getId(), "가로챈 내용")))))
                    .isInstanceOf(BadRequestException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_REQUIREMENT_NOT_OWNED);

            assertThat(featureRequirementRepository.findById(stranger.getId()).orElseThrow().getContent())
                    .isEqualTo("댓글을 작성한다.");
        }

        @Test
        @DisplayName("같은 요구사항 id를 두 번 보내면 거절한다")
        void rejectsDuplicateRequirementId() {
            Feature feature = newFeature("게시글", section, 0);
            FeatureRequirement first = newRequirement(feature, "게시글을 작성한다.", 0);
            FeatureRequirement second = newRequirement(feature, "게시글을 수정한다.", 1);

            assertThatThrownBy(() -> featureReviewService.update(
                    specDocumentId, userId, feature.getId(),
                    new FeatureUpdateRequest(null, List.of(
                            new FeatureUpdateRequest.Requirement(first.getId(), "수정1"),
                            new FeatureUpdateRequest.Requirement(first.getId(), "수정2")))))
                    .isInstanceOf(BadRequestException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_REQUIREMENT_DUPLICATED);

            // 조용히 접히면 2건이 1건이 되고, 보내지 않은 second가 삭제된다.
            assertThat(featureRequirementRepository
                    .findAllByFeatureIdOrderByDisplayOrderAscIdAsc(feature.getId()))
                    .extracting(FeatureRequirement::getId)
                    .containsExactly(first.getId(), second.getId());
        }

        @Test
        @DisplayName("수정하면 AI 산출물을 전부 지운다")
        void clearsAiOutput() {
            Feature feature = newFeature("게시글", section, 0);
            Feature target = newFeature("글쓰기", section, 1);
            FeatureRequirement requirement = newRequirement(feature, "게시글을 작성한다.", 0);
            newIssue(feature, FeatureIssueType.DUPLICATE_SUSPECTED);
            newIssue(feature, FeatureIssueType.SPLIT_RECOMMENDED);
            newCandidate(feature, target, "게시글 관리", "게시글");
            newSuggestion(feature, "게시글 관리", "게시글", 0, requirement);

            featureReviewService.update(specDocumentId, userId, feature.getId(),
                    new FeatureUpdateRequest("게시글 관리", null));

            assertThat(featureIssueRepository.findAllByFeatureIdIn(List.of(feature.getId()))).isEmpty();
            assertThat(duplicateCandidateRepository.findAllByFeatureIdIn(List.of(feature.getId()))).isEmpty();
            assertThat(splitFeatureSuggestionRepository
                    .findAllByFeatureIdOrderByDisplayOrderAscIdAsc(feature.getId())).isEmpty();
            // 분리 제안이 사라지면 그 연결도 함께 사라져야 한다. 남으면 이미 적용할 수 없는
            // 추천안을 계속 가리킨다.
            assertThat(splitSuggestionFeatureRequirementRepository.findAll()).isEmpty();
            assertThat(reload(feature).getReviewStatus()).isEqualTo(FeatureReviewStatus.USER_MODIFIED);
        }

        @Test
        @DisplayName("수정하면 이 기능을 가리키던 중복 후보와 그쪽 배지도 정리한다")
        void clearsIncomingDuplicateCandidates() {
            Feature holder = newFeature("포스트 등록", section, 0);
            Feature edited = newFeature("게시글 작성", section, 1);
            newIssue(holder, FeatureIssueType.DUPLICATE_SUSPECTED);
            newCandidate(holder, edited, "게시글 관리", "게시글");

            featureReviewService.update(specDocumentId, userId, edited.getId(),
                    new FeatureUpdateRequest("게시글 등록", null));

            // holder의 제안은 수정 전 edited를 설명하던 것이라 근거가 무너졌다.
            assertThat(duplicateCandidateRepository.findAllByFeatureIdIn(List.of(holder.getId())))
                    .isEmpty();
            assertThat(featureIssueRepository.findAllByFeatureIdIn(List.of(holder.getId())))
                    .isEmpty();
            assertThat(reload(holder).getReviewStatus()).isEqualTo(FeatureReviewStatus.UNREVIEWED);
        }

        @Test
        @DisplayName("승인은 이 기능을 가리키던 중복 후보를 건드리지 않는다")
        void keepsIncomingDuplicateCandidatesOnConfirm() {
            Feature holder = newFeature("포스트 등록", section, 0);
            Feature confirmed = newFeature("게시글 작성", section, 1);
            newIssue(holder, FeatureIssueType.DUPLICATE_SUSPECTED);
            newCandidate(holder, confirmed, "게시글 관리", "게시글");

            featureReviewService.confirm(specDocumentId, userId, confirmed.getId());

            // 승인은 confirmed 자신의 특이사항에 대한 답이지 holder의 중복 질문에 대한 답이 아니다.
            assertThat(duplicateCandidateRepository.findAllByFeatureIdIn(List.of(holder.getId())))
                    .hasSize(1);
            assertThat(featureIssueRepository.findAllByFeatureIdIn(List.of(holder.getId())))
                    .extracting(FeatureIssue::getIssueType)
                    .containsExactly(FeatureIssueType.DUPLICATE_SUSPECTED);
        }

        @Test
        @DisplayName("바꿀 값이 하나도 없으면 거절한다")
        void rejectsEmptyRequest() {
            Feature feature = newFeature("게시글", section, 0);

            assertThatThrownBy(() -> featureReviewService.update(
                    specDocumentId, userId, feature.getId(),
                    new FeatureUpdateRequest(null, null)))
                    .isInstanceOf(BadRequestException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_UPDATE_EMPTY);
        }
    }

    @Nested
    @DisplayName("기능 승인")
    class Confirm {

        @Test
        @DisplayName("승인하면 AI 산출물을 지우고 상태를 바꾼다")
        void confirmsAndClearsAiOutput() {
            Feature feature = newFeature("게시글", section, 0);
            newIssue(feature, FeatureIssueType.SOURCE_REVIEW_REQUIRED);

            featureReviewService.confirm(specDocumentId, userId, feature.getId());

            assertThat(reload(feature).getReviewStatus()).isEqualTo(FeatureReviewStatus.USER_CONFIRMED);
            assertThat(featureIssueRepository.findAllByFeatureIdIn(List.of(feature.getId()))).isEmpty();
        }

        @Test
        @DisplayName("수정한 기능을 승인해도 USER_MODIFIED로 남는다")
        void keepsModifiedStatusOnConfirm() {
            Feature feature = newFeature("게시글", section, 0);
            featureReviewService.update(specDocumentId, userId, feature.getId(),
                    new FeatureUpdateRequest("게시글 관리", null));

            featureReviewService.confirm(specDocumentId, userId, feature.getId());

            assertThat(reload(feature).getReviewStatus()).isEqualTo(FeatureReviewStatus.USER_MODIFIED);
        }

        @Test
        @DisplayName("이미 승인한 기능에 다시 요청해도 오류가 아니다")
        void isIdempotent() {
            Feature feature = newFeature("게시글", section, 0);

            featureReviewService.confirm(specDocumentId, userId, feature.getId());
            featureReviewService.confirm(specDocumentId, userId, feature.getId());

            assertThat(reload(feature).getReviewStatus()).isEqualTo(FeatureReviewStatus.USER_CONFIRMED);
        }
    }

    @Nested
    @DisplayName("기능 병합")
    class Merge {

        @Test
        @DisplayName("새 기능을 만들고 두 기능을 지운다")
        void createsMergedFeatureAndDeletesBoth() {
            Feature source = newFeature("게시글 작성", section, 2, 3, 5);
            Feature target = newFeature("글쓰기", section, 7, 1, 4);
            newRequirement(source, "게시글을 작성한다.", 0);
            newRequirement(target, "글을 쓴다.", 0);
            newIssue(source, FeatureIssueType.DUPLICATE_SUSPECTED);
            newCandidate(source, target, "게시글 관리", "게시글");

            featureReviewService.merge(specDocumentId, userId, source.getId(),
                    new FeatureMergeRequest(target.getId(), "게시글 관리"));

            List<Feature> remaining = featureRepository.findAllForReview(specDocumentId);

            assertThat(remaining).singleElement().satisfies(merged -> {
                assertThat(merged.getName()).isEqualTo("게시글 관리");
                assertThat(merged.getReviewStatus()).isEqualTo(FeatureReviewStatus.USER_MODIFIED);
                assertThat(merged.getSection().getId()).isEqualTo(section.getId());
                // 두 기능 중 앞선 자리
                assertThat(merged.getDisplayOrder()).isEqualTo(2);
                // 두 기능을 합친 원문 범위
                assertThat(merged.getSourcePageStart()).isEqualTo(1);
                assertThat(merged.getSourcePageEnd()).isEqualTo(5);
            });

            assertThat(featureRequirementRepository
                    .findAllByFeatureIdOrderByDisplayOrderAscIdAsc(remaining.getFirst().getId()))
                    .extracting(FeatureRequirement::getContent)
                    .containsExactly("게시글을 작성한다.", "글을 쓴다.");
            // 특이사항은 물려받지 않는다
            assertThat(featureIssueRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("한쪽 페이지 범위가 비어 있어도 나머지를 쓴다")
        void handlesMissingPageRange() {
            Feature source = newFeature("게시글 작성", section, 0, null, null);
            Feature target = newFeature("글쓰기", section, 1, 2, 6);
            newCandidate(source, target, "게시글 관리", "게시글");

            featureReviewService.merge(specDocumentId, userId, source.getId(),
                    new FeatureMergeRequest(target.getId(), "게시글 관리"));

            assertThat(featureRepository.findAllForReview(specDocumentId)).singleElement()
                    .satisfies(merged -> {
                        assertThat(merged.getSourcePageStart()).isEqualTo(2);
                        assertThat(merged.getSourcePageEnd()).isEqualTo(6);
                    });
        }

        @Test
        @DisplayName("제안된 분류가 없으면 원문 정보 없이 새로 만든다")
        void createsSuggestedSection() {
            Feature source = newFeature("게시글 작성", section, 0);
            Feature target = newFeature("글쓰기", section, 1);
            newCandidate(source, target, "게시글 관리", "커뮤니티");

            featureReviewService.merge(specDocumentId, userId, source.getId(),
                    new FeatureMergeRequest(target.getId(), "게시글 관리"));

            FeatureSection created = featureRepository.findAllForReview(specDocumentId)
                    .getFirst().getSection();

            assertThat(created.getTitle()).isEqualTo("커뮤니티");
            assertThat(created.getSourceTitle()).isNull();
            assertThat(created.getPageStart()).isNull();
            assertThat(created.getPageEnd()).isNull();
            assertThat(created.getDisplayOrder()).isEqualTo(section.getDisplayOrder() + 1);
        }

        @Test
        @DisplayName("제안된 분류명에 앞뒤 공백이 있어도 기존 분류를 찾아낸다")
        void matchesSectionIgnoringSurroundingWhitespace() {
            Feature source = newFeature("게시글 작성", section, 0);
            Feature target = newFeature("글쓰기", section, 1);
            newCandidate(source, target, "게시글 관리", "  게시글  ");

            featureReviewService.merge(specDocumentId, userId, source.getId(),
                    new FeatureMergeRequest(target.getId(), "게시글 관리"));

            Feature merged = featureRepository.findAllForReview(specDocumentId).getFirst();

            // 미분류로 떨어지거나 같은 이름의 분류가 하나 더 생기면 안 된다.
            assertThat(merged.getSection()).isNotNull();
            assertThat(merged.getSection().getId()).isEqualTo(section.getId());
            assertThat(featureSectionRepository.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("중복으로 지목되지 않은 기능은 합칠 수 없다")
        void rejectsUnrelatedFeature() {
            Feature source = newFeature("게시글 작성", section, 0);
            Feature other = newFeature("좋아요", section, 1);

            assertThatThrownBy(() -> featureReviewService.merge(
                    specDocumentId, userId, source.getId(),
                    new FeatureMergeRequest(other.getId(), "합친 기능")))
                    .isInstanceOf(BadRequestException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_MERGE_NOT_ALLOWED);
        }

        @Test
        @DisplayName("다른 문서의 기능을 합칠 상대로 보내면 404다")
        void rejectsTargetFromAnotherDocument() {
            Feature source = newFeature("게시글 작성", section, 0);

            User owner = userRepository.findById(userId).orElseThrow();
            Project otherProject = projectRepository.save(Project.create(owner, "다른 프로젝트"));
            SpecDocument otherDocument = specDocumentRepository.save(SpecDocument.builder()
                    .project(otherProject).user(owner).fileName("다른.pdf").build());
            Feature outsider = featureRepository.save(Feature.builder()
                    .specDocument(otherDocument).name("남의 문서 기능").displayOrder(0).build());

            assertThatThrownBy(() -> featureReviewService.merge(
                    specDocumentId, userId, source.getId(),
                    new FeatureMergeRequest(outsider.getId(), "합친 기능")))
                    .isInstanceOf(NotFoundException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_NOT_ACCESSIBLE);
        }

        @Test
        @DisplayName("지목되지 않은 반대 방향으로는 합칠 수 없다")
        void rejectsReverseDirection() {
            Feature source = newFeature("게시글 작성", section, 0);
            Feature target = newFeature("글쓰기", section, 1);
            newCandidate(source, target, "게시글 관리", "게시글");

            assertThatThrownBy(() -> featureReviewService.merge(
                    specDocumentId, userId, target.getId(),
                    new FeatureMergeRequest(source.getId(), "게시글 관리")))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    @DisplayName("기능 분리")
    class Split {

        @Test
        @DisplayName("추천안대로 새 기능들을 만들고 원래 기능을 지운다")
        void createsFeaturesFromSuggestions() {
            Feature source = newFeature("게시글", section, 4, 3, 8);
            FeatureRequirement write = newRequirement(source, "게시글을 작성한다.", 0);
            FeatureRequirement comment = newRequirement(source, "댓글을 작성한다.", 1);
            newIssue(source, FeatureIssueType.SPLIT_RECOMMENDED);
            SplitFeatureSuggestion first = newSuggestion(source, "게시글 관리", "게시글", 0, write);
            SplitFeatureSuggestion second = newSuggestion(source, "댓글 관리", "댓글", 1, comment);

            featureReviewService.split(specDocumentId, userId, source.getId(),
                    new FeatureSplitRequest(List.of(
                            new FeatureSplitRequest.Target(first.getId(), "게시글 관리"),
                            new FeatureSplitRequest.Target(second.getId(), "댓글"))));

            List<Feature> created = featureRepository.findAllForReview(specDocumentId);

            assertThat(created).extracting(Feature::getName).containsExactly("게시글 관리", "댓글");
            assertThat(created).allSatisfy(feature -> {
                assertThat(feature.getReviewStatus()).isEqualTo(FeatureReviewStatus.USER_MODIFIED);
                // 원래 기능의 자리와 원문 범위를 물려받는다
                assertThat(feature.getDisplayOrder()).isEqualTo(4);
                assertThat(feature.getSourcePageStart()).isEqualTo(3);
                assertThat(feature.getSourcePageEnd()).isEqualTo(8);
            });

            assertThat(featureRequirementRepository
                    .findAllByFeatureIdOrderByDisplayOrderAscIdAsc(created.getFirst().getId()))
                    .singleElement()
                    .satisfies(requirement -> {
                        assertThat(requirement.getContent()).isEqualTo("게시글을 작성한다.");
                        // 원문 근거도 함께 복사한다
                        assertThat(requirement.getSourceText()).isEqualTo("원문: 게시글을 작성한다.");
                    });
            assertThat(featureIssueRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("추천 분류명에 앞뒤 공백이 있어도 기존 분류를 찾아낸다")
        void matchesSectionIgnoringSurroundingWhitespace() {
            Feature source = newFeature("게시글", section, 0);
            FeatureRequirement write = newRequirement(source, "게시글을 작성한다.", 0);
            FeatureRequirement comment = newRequirement(source, "댓글을 작성한다.", 1);
            SplitFeatureSuggestion first = newSuggestion(source, "게시글 관리", " 게시글 ", 0, write);
            SplitFeatureSuggestion second = newSuggestion(source, "댓글 관리", "게시글", 1, comment);

            featureReviewService.split(specDocumentId, userId, source.getId(),
                    new FeatureSplitRequest(List.of(
                            new FeatureSplitRequest.Target(first.getId(), "게시글 관리"),
                            new FeatureSplitRequest.Target(second.getId(), "댓글 관리"))));

            assertThat(featureRepository.findAllForReview(specDocumentId))
                    .allSatisfy(feature -> assertThat(feature.getSection().getId())
                            .isEqualTo(section.getId()));
            assertThat(featureSectionRepository.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("추천안이 없으면 나눌 수 없다")
        void rejectsFeatureWithoutSuggestion() {
            Feature source = newFeature("게시글", section, 0);

            assertThatThrownBy(() -> featureReviewService.split(
                    specDocumentId, userId, source.getId(),
                    new FeatureSplitRequest(List.of(
                            new FeatureSplitRequest.Target(1L, "게시글 관리")))))
                    .isInstanceOf(BadRequestException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPLIT_NOT_ALLOWED);
        }

        @Test
        @DisplayName("같은 분리 추천안을 두 번 보내면 거절한다")
        void rejectsDuplicateSuggestionId() {
            Feature source = newFeature("게시글", section, 0);
            FeatureRequirement write = newRequirement(source, "게시글을 작성한다.", 0);
            FeatureRequirement comment = newRequirement(source, "댓글을 작성한다.", 1);
            SplitFeatureSuggestion first = newSuggestion(source, "게시글 관리", "게시글", 0, write);
            SplitFeatureSuggestion second = newSuggestion(source, "댓글 관리", "댓글", 1, comment);

            assertThatThrownBy(() -> featureReviewService.split(
                    specDocumentId, userId, source.getId(),
                    new FeatureSplitRequest(List.of(
                            new FeatureSplitRequest.Target(first.getId(), "게시글 관리"),
                            new FeatureSplitRequest.Target(first.getId(), "게시글 보관"),
                            new FeatureSplitRequest.Target(second.getId(), "댓글 관리")))))
                    .isInstanceOf(BadRequestException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPLIT_NOT_ALLOWED);

            assertThat(featureRepository.findAllForReview(specDocumentId))
                    .extracting(Feature::getId)
                    .containsExactly(source.getId());
        }

        @Test
        @DisplayName("추천안 일부만 보내면 거절한다")
        void rejectsPartialSuggestions() {
            Feature source = newFeature("게시글", section, 0);
            FeatureRequirement write = newRequirement(source, "게시글을 작성한다.", 0);
            FeatureRequirement comment = newRequirement(source, "댓글을 작성한다.", 1);
            SplitFeatureSuggestion first = newSuggestion(source, "게시글 관리", "게시글", 0, write);
            newSuggestion(source, "댓글 관리", "댓글", 1, comment);

            assertThatThrownBy(() -> featureReviewService.split(
                    specDocumentId, userId, source.getId(),
                    new FeatureSplitRequest(List.of(
                            new FeatureSplitRequest.Target(first.getId(), "게시글 관리")))))
                    .isInstanceOf(BadRequestException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPLIT_NOT_ALLOWED);

            assertThat(featureRepository.findAllForReview(specDocumentId)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("기능 삭제와 고아 정리")
    class Delete {

        @Test
        @DisplayName("기능과 딸린 데이터를 함께 지운다")
        void deletesFeatureWithChildren() {
            Feature feature = newFeature("게시글", section, 0);
            newRequirement(feature, "게시글을 작성한다.", 0);
            newIssue(feature, FeatureIssueType.SOURCE_REVIEW_REQUIRED);

            featureReviewService.delete(specDocumentId, userId, feature.getId());

            assertThat(featureRepository.findAllForReview(specDocumentId)).isEmpty();
            assertThat(featureRequirementRepository.findAll()).isEmpty();
            assertThat(featureIssueRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("합칠 상대가 사라진 중복 의심 표시를 정리한다")
        void clearsOrphanDuplicateIssue() {
            Feature holder = newFeature("포스트 등록", section, 0);
            Feature target = newFeature("게시글 작성", section, 1);
            newIssue(holder, FeatureIssueType.DUPLICATE_SUSPECTED);
            newCandidate(holder, target, "게시글 관리", "게시글");

            // 상대를 지우면 holder→target 행은 CASCADE로 사라지지만 배지는 holder에 남는다.
            featureReviewService.delete(specDocumentId, userId, target.getId());

            assertThat(duplicateCandidateRepository.findAllByFeatureIdIn(List.of(holder.getId()))).isEmpty();
            assertThat(featureIssueRepository.findAllByFeatureIdIn(List.of(holder.getId()))).isEmpty();
        }

        @Test
        @DisplayName("다른 특이사항까지 지우지는 않는다")
        void keepsUnrelatedIssues() {
            Feature holder = newFeature("포스트 등록", section, 0);
            Feature target = newFeature("게시글 작성", section, 1);
            newIssue(holder, FeatureIssueType.DUPLICATE_SUSPECTED);
            newIssue(holder, FeatureIssueType.SOURCE_REVIEW_REQUIRED);
            newCandidate(holder, target, "게시글 관리", "게시글");

            featureReviewService.delete(specDocumentId, userId, target.getId());

            assertThat(featureIssueRepository.findAllByFeatureIdIn(List.of(holder.getId())))
                    .extracting(FeatureIssue::getIssueType)
                    .containsExactly(FeatureIssueType.SOURCE_REVIEW_REQUIRED);
        }

        @Test
        @DisplayName("병합으로 기능이 사라져도 고아 표시를 정리한다")
        void clearsOrphanAfterMerge() {
            Feature source = newFeature("게시글 작성", section, 0);
            Feature target = newFeature("글쓰기", section, 1);
            Feature bystander = newFeature("포스트 등록", section, 2);
            newIssue(source, FeatureIssueType.DUPLICATE_SUSPECTED);
            newCandidate(source, target, "게시글 관리", "게시글");
            newIssue(bystander, FeatureIssueType.DUPLICATE_SUSPECTED);
            newCandidate(bystander, target, "게시글 관리", "게시글");

            featureReviewService.merge(specDocumentId, userId, source.getId(),
                    new FeatureMergeRequest(target.getId(), "게시글 관리"));

            assertThat(featureIssueRepository.findAllByFeatureIdIn(List.of(bystander.getId()))).isEmpty();
        }
    }

    @Nested
    @DisplayName("일괄 승인과 요약")
    class ConfirmAllAndSummary {

        @Test
        @DisplayName("확인하지 않은 기능만 승인하고 이미 검토한 기능은 두는다")
        void confirmsOnlyUnreviewed() {
            Feature unreviewed = newFeature("게시글", section, 0);
            Feature modified = newFeature("댓글", section, 1);
            newIssue(unreviewed, FeatureIssueType.SOURCE_REVIEW_REQUIRED);
            featureReviewService.update(specDocumentId, userId, modified.getId(),
                    new FeatureUpdateRequest("댓글 관리", null));

            featureReviewService.confirmAll(specDocumentId, userId);

            assertThat(reload(unreviewed).getReviewStatus()).isEqualTo(FeatureReviewStatus.USER_CONFIRMED);
            assertThat(reload(modified).getReviewStatus()).isEqualTo(FeatureReviewStatus.USER_MODIFIED);
            // 특이사항이 있어도 함께 승인하고 그 산출물은 지운다
            assertThat(featureIssueRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("요약은 겹치지 않는 세 값으로 나뉘고 합이 전체와 같다")
        void summarizesReviewProgress() {
            // 세 값을 서로 다르게 만든다. 전부 같으면 자리를 바꿔 넣어도 통과한다.
            Feature firstIssue = newFeature("게시글", section, 0);
            Feature secondIssue = newFeature("첨부파일", section, 1);
            newFeature("댓글", section, 2);
            Feature confirmed = newFeature("좋아요", section, 3);
            Feature modified = newFeature("신고", section, 4);
            Feature alsoConfirmed = newFeature("공지", section, 5);
            newIssue(firstIssue, FeatureIssueType.MISSING_REQUIREMENTS);
            newIssue(secondIssue, FeatureIssueType.SOURCE_REVIEW_REQUIRED);
            featureReviewService.confirm(specDocumentId, userId, confirmed.getId());
            featureReviewService.confirm(specDocumentId, userId, alsoConfirmed.getId());
            featureReviewService.update(specDocumentId, userId, modified.getId(),
                    new FeatureUpdateRequest("신고 관리", null));

            FeatureReviewSummaryResponse summary =
                    featureReviewService.summary(specDocumentId, userId);

            assertThat(summary.reviewRequired()).isEqualTo(2);
            assertThat(summary.noIssue()).isEqualTo(1);
            assertThat(summary.reviewed()).isEqualTo(3);
            assertThat(summary.total()).isEqualTo(6);
        }
    }

    private FeatureReviewResponse list(FeatureReviewFilter filter) {
        return featureReviewService.list(specDocumentId, userId, filter);
    }

    private List<String> featureNames(FeatureReviewResponse response) {
        return response.sections().stream()
                .flatMap(group -> group.features().stream())
                .map(FeatureReviewResponse.Feature::name)
                .toList();
    }

    private Feature reload(Feature feature) {
        return featureRepository.findById(feature.getId()).orElseThrow();
    }

    private Feature newFeature(String name, FeatureSection section, int displayOrder) {
        return newFeature(name, section, displayOrder, 1, 2);
    }

    private Feature newFeature(String name, FeatureSection section, int displayOrder,
                               Integer pageStart, Integer pageEnd) {
        return featureRepository.save(Feature.builder()
                .specDocument(specDocument)
                .section(section)
                .name(name)
                .displayOrder(displayOrder)
                .sourcePageStart(pageStart)
                .sourcePageEnd(pageEnd)
                .build());
    }

    private FeatureRequirement newRequirement(Feature feature, String content, int displayOrder) {
        return featureRequirementRepository.save(FeatureRequirement.builder()
                .feature(feature)
                .content(content)
                .sourceText("원문: " + content)
                .displayOrder(displayOrder)
                .build());
    }

    private FeatureIssue newIssue(Feature feature, FeatureIssueType issueType) {
        return featureIssueRepository.save(FeatureIssue.builder()
                .feature(feature)
                .issueType(issueType)
                .description(issueType + " 설명")
                .build());
    }

    private DuplicateCandidate newCandidate(Feature feature, Feature target,
                                            String suggestedMergedName, String suggestedSection) {
        return duplicateCandidateRepository.save(DuplicateCandidate.builder()
                .feature(feature)
                .targetFeature(target)
                .reason("요구사항이 겹친다.")
                .suggestedMergedName(suggestedMergedName)
                .suggestedSection(suggestedSection)
                .build());
    }

    private SplitFeatureSuggestion newSuggestion(Feature feature, String suggestedName,
                                                 String suggestedSection, int displayOrder,
                                                 FeatureRequirement... requirements) {
        SplitFeatureSuggestion suggestion =
                splitFeatureSuggestionRepository.save(SplitFeatureSuggestion.builder()
                        .feature(feature)
                        .suggestedName(suggestedName)
                        .suggestedSection(suggestedSection)
                        .displayOrder(displayOrder)
                        .build());

        List<SplitSuggestionFeatureRequirement> links = new ArrayList<>();

        for (FeatureRequirement requirement : Arrays.asList(requirements)) {
            links.add(SplitSuggestionFeatureRequirement.builder()
                    .splitFeatureSuggestion(suggestion)
                    .featureRequirement(requirement)
                    .build());
        }

        splitSuggestionFeatureRequirementRepository.saveAll(links);

        return suggestion;
    }
}
