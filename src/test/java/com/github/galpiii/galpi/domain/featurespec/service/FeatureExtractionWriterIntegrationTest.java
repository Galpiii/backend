package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.DuplicateCandidate;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Issue;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Requirement;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Section;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.SplitSuggestion;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Source;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.SuggestedFeature;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionFailureCode;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
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
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("기능명세서 추출 결과 저장 — 실제 Postgres")
class FeatureExtractionWriterIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private FeatureExtractionWriter writer;
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
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long specDocumentId;
    private User user;
    private Project project;

    @BeforeEach
    void setUp() {
        specDocumentRepository.deleteAllInBatch();
        projectRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        user = userRepository.save(
                User.ofGithub(System.nanoTime(), "galpi-tester", "테스터", null, null));
        project = projectRepository.save(Project.create(user, "갈피"));
        SpecDocument specDocument = specDocumentRepository.save(
                SpecDocument.builder().project(project).user(user).fileName("기능명세서.pdf").build());

        specDocumentId = specDocument.getId();
        writer.markProcessing(specDocumentId);
    }

    private SpecDocument reloadSpecDocument() {
        return specDocumentRepository.findById(specDocumentId).orElseThrow();
    }

    /**
     * 커뮤니티 기능 하나에 중복 후보와 분리 제안이 모두 붙은 결과.
     * 저장 계층이 다루는 7개 테이블을 한 번에 채운다.
     */
    private FeatureSpecExtractionResult fullResult() {
        FeatureSpecExtractionResult.Feature community = new FeatureSpecExtractionResult.Feature(
                "f1",
                "커뮤니티 기능",
                "커뮤니티",
                List.of(new Requirement("게시글을 작성한다.", "원문 게시글"),
                        new Requirement("댓글을 작성한다.", "원문 댓글")),
                new Source(9, 10),
                List.of(new Issue("SPLIT_RECOMMENDED", "독립적인 기능이 묶였다."),
                        new Issue("DUPLICATE_SUSPECTED", "게시판 관리와 겹친다.")),
                List.of(new DuplicateCandidate("f2", "요구사항이 같다.", "게시글 관리", "커뮤니티")),
                new SplitSuggestion(List.of(
                        new SuggestedFeature("게시글 관리", List.of(0), "커뮤니티"),
                        new SuggestedFeature("댓글 관리", List.of(1), "커뮤니티"))));

        FeatureSpecExtractionResult.Feature board = new FeatureSpecExtractionResult.Feature(
                "f2",
                "게시판 관리",
                null,
                List.of(new Requirement("게시판을 만든다.", "원문 게시판")),
                new Source(11, 11),
                List.of(),
                List.of(),
                null);

        return new FeatureSpecExtractionResult(
                List.of(new Section("커뮤니티", "4. 커뮤니티 및 게시판", 9, 13)),
                List.of(community, board));
    }

    @Nested
    @DisplayName("추출 결과 저장")
    class SaveResult {

        @Test
        @DisplayName("7개 테이블에 결과를 남기고 COMPLETED로 바꾼다")
        void persistsEveryTable() {
            writer.saveResult(specDocumentId, fullResult());

            assertThat(featureSectionRepository.count()).isEqualTo(1);
            assertThat(featureRepository.count()).isEqualTo(2);
            assertThat(featureRequirementRepository.count()).isEqualTo(3);
            assertThat(featureIssueRepository.count()).isEqualTo(2);
            assertThat(duplicateCandidateRepository.count()).isEqualTo(1);
            assertThat(splitFeatureSuggestionRepository.count()).isEqualTo(2);
            assertThat(splitSuggestionFeatureRequirementRepository.count()).isEqualTo(2);
            assertThat(reloadSpecDocument().getExtractionStatus()).isEqualTo(ExtractionStatus.COMPLETED);
        }

        @Test
        @DisplayName("기능을 섹션에 연결하고, 섹션이 없는 기능은 미분류로 둔다")
        void linksFeatureToSection() {
            writer.saveResult(specDocumentId, fullResult());

            transactionTemplate.executeWithoutResult(status -> {
                var features = featureRepository.findAll().stream()
                        .sorted((a, b) -> Integer.compare(a.getDisplayOrder(), b.getDisplayOrder()))
                        .toList();

                assertThat(features.getFirst().getSection().getTitle()).isEqualTo("커뮤니티");
                assertThat(features.getFirst().getSection().getSourceTitle())
                        .isEqualTo("4. 커뮤니티 및 게시판");
                assertThat(features.getLast().getSection()).isNull();
            });
        }

        @Test
        @DisplayName("분리 제안이 index에 해당하는 요구사항을 정확히 가리킨다")
        void linksSuggestionToRequirementByIndex() {
            writer.saveResult(specDocumentId, fullResult());

            transactionTemplate.executeWithoutResult(status ->
                    assertThat(splitSuggestionFeatureRequirementRepository.findAll())
                            .extracting(link -> link.getSplitFeatureSuggestion().getSuggestedName()
                                    + " -> " + link.getFeatureRequirement().getContent())
                            .containsExactlyInAnyOrder(
                                    "게시글 관리 -> 게시글을 작성한다.",
                                    "댓글 관리 -> 댓글을 작성한다."));
        }

        @Test
        @DisplayName("중복 후보가 대상 기능을 가리킨다")
        void linksDuplicateCandidateToTarget() {
            writer.saveResult(specDocumentId, fullResult());

            transactionTemplate.executeWithoutResult(status -> {
                var candidate = duplicateCandidateRepository.findAll().getFirst();

                assertThat(candidate.getFeature().getName()).isEqualTo("커뮤니티 기능");
                assertThat(candidate.getTargetFeature().getName()).isEqualTo("게시판 관리");
            });
        }

        @Test
        @DisplayName("displayOrder가 응답 배열 순서를 따른다")
        void keepsArrayOrder() {
            writer.saveResult(specDocumentId, fullResult());

            transactionTemplate.executeWithoutResult(status -> {
                assertThat(featureRepository.findAll())
                        .extracting(feature -> feature.getName() + ":" + feature.getDisplayOrder())
                        .containsExactlyInAnyOrder("커뮤니티 기능:0", "게시판 관리:1");

                assertThat(featureRequirementRepository.findAll())
                        .filteredOn(requirement -> requirement.getFeature().getName().equals("커뮤니티 기능"))
                        .extracting(requirement ->
                                requirement.getContent() + ":" + requirement.getDisplayOrder())
                        .containsExactlyInAnyOrder("게시글을 작성한다.:0", "댓글을 작성한다.:1");
            });
        }

        /**
         * 저장이 중간에 실패했는데 앞부분만 남으면, 화면은 정상처럼 보이면서 실제로는 빠진 기능으로
         * PR 대조가 돌아간다. 사용자가 알아챌 방법이 없으므로 전부 롤백되어야 한다.
         */
        @Test
        @DisplayName("저장 도중 실패하면 아무 행도 남지 않고 상태도 그대로다")
        void rollsBackEverythingOnFailure() {
            FeatureSpecExtractionResult broken = new FeatureSpecExtractionResult(
                    List.of(new Section("커뮤니티", "4. 커뮤니티", 9, 13)),
                    List.of(
                            new FeatureSpecExtractionResult.Feature("f1", "정상 기능", "커뮤니티",
                                    List.of(new Requirement("정상이다.", "원문")),
                                    new Source(1, 1), List.of(), List.of(), null),
                            new FeatureSpecExtractionResult.Feature("f2", "깨진 기능", "커뮤니티",
                                    List.of(new Requirement(null, "원문")),
                                    new Source(2, 2), List.of(), List.of(), null)));

            assertThatThrownBy(() -> writer.saveResult(specDocumentId, broken))
                    .isInstanceOf(RuntimeException.class);

            assertThat(featureSectionRepository.count()).isZero();
            assertThat(featureRepository.count()).isZero();
            assertThat(featureRequirementRepository.count()).isZero();
            assertThat(reloadSpecDocument().getExtractionStatus()).isEqualTo(ExtractionStatus.PROCESSING);
        }
    }

    @Nested
    @DisplayName("상태 전이")
    class StatusTransition {

        /**
         * 재배포로 프로세스가 죽으면 큐에 있던 작업과 임시 PDF가 함께 사라진다. 남은 문서를
         * 그대로 두면 프론트가 끝나지 않는 상태를 계속 polling한다.
         */
        @Test
        @DisplayName("끝나지 않은 분석을 한 번에 실패로 정리한다")
        void failsAllInProgress() {
            SpecDocument pending = specDocumentRepository.save(SpecDocument.builder()
                    .project(project)
                    .user(user)
                    .fileName("대기중.pdf")
                    .build());

            writer.saveResult(specDocumentId, fullResult());

            int cleaned = writer.failAllInProgress();

            assertThat(cleaned).isEqualTo(1);
            assertThat(specDocumentRepository.findById(pending.getId()).orElseThrow())
                    .satisfies(document -> {
                        assertThat(document.getExtractionStatus()).isEqualTo(ExtractionStatus.FAILED);
                        assertThat(document.getFailureCode())
                                .isEqualTo(ExtractionFailureCode.ANALYSIS_FAILED);
                    });
            assertThat(reloadSpecDocument().getExtractionStatus())
                    .isEqualTo(ExtractionStatus.COMPLETED);
        }

        @Test
        @DisplayName("분석을 시작하면 PROCESSING이 된다")
        void marksProcessing() {
            assertThat(reloadSpecDocument().getExtractionStatus()).isEqualTo(ExtractionStatus.PROCESSING);
        }

        @Test
        @DisplayName("실패하면 상태와 사유를 함께 남긴다")
        void marksFailedWithCode() {
            writer.markFailed(specDocumentId, ExtractionFailureCode.NO_FEATURE_EXTRACTED);

            SpecDocument specDocument = reloadSpecDocument();
            assertThat(specDocument.getExtractionStatus()).isEqualTo(ExtractionStatus.FAILED);
            assertThat(specDocument.getFailureCode()).isEqualTo(ExtractionFailureCode.NO_FEATURE_EXTRACTED);
        }

        /**
         * 실패한 적이 있는 문서에 사유가 남아 있으면 ck_spec_documents_failure_code_only_when_failed
         * 제약에 걸려 저장 전체가 롤백된다.
         */
        @Test
        @DisplayName("COMPLETED로 갈 때 실패 사유를 비운다")
        void clearsFailureCodeOnCompletion() {
            writer.markFailed(specDocumentId, ExtractionFailureCode.ANALYSIS_FAILED);

            writer.saveResult(specDocumentId, fullResult());

            SpecDocument specDocument = reloadSpecDocument();
            assertThat(specDocument.getExtractionStatus()).isEqualTo(ExtractionStatus.COMPLETED);
            assertThat(specDocument.getFailureCode()).isNull();
        }
    }
}
