package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecUploadResponse;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.Feature;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureIssue;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureIssueType;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureRequirement;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureSection;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureIssueRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureRequirementRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureSectionRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.entity.ProjectOnboardingStep;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecFileValidator;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.spy;

@DisplayName("기능명세서 교체 — 실제 Postgres")
class FeatureSpecReplaceIntegrationTest extends IntegrationTestSupport {

    private static final String FILE_NAME = "기능명세서.pdf";

    @Autowired
    private FeatureSpecService service;
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
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecTempFileStore tempFileStore;

    /** 분석 스레드가 살아 있으면 다음 테스트가 DB를 비우는 사이 상태를 써서 서로를 간섭한다. */
    @MockitoBean
    private FeatureExtractionService featureExtractionService;

    /** 상태 확인과 삭제 사이에 다른 요청이 끼어든 상황을 만들기 위해 검증 시점을 가로챈다. */
    @MockitoSpyBean
    private FeatureSpecFileValidator featureSpecFileValidator;

    private Long userId;
    private Long projectId;
    private Long currentSpecDocumentId;

    @BeforeEach
    void setUp() {
        willAnswer(invocation -> {
            tempFileStore.delete(invocation.getArgument(1, File.class));
            return null;
        }).given(featureExtractionService).extract(anyLong(), any(File.class));

        User user = userRepository.save(
                User.ofGithub(System.nanoTime(), "galpi-tester", "테스터", null, null));
        Project project = projectRepository.save(Project.create(user, "갈피"));

        userId = user.getId();
        projectId = project.getId();
        currentSpecDocumentId = givenCompletedSpecDocumentWithResult(project, user);
    }

    /** 분석까지 끝나 결과와 검토 기록이 쌓인 상태를 만든다. */
    private Long givenCompletedSpecDocumentWithResult(Project project, User user) {
        SpecDocument specDocument = specDocumentRepository.saveAndFlush(SpecDocument.builder()
                .project(project).user(user).fileName(FILE_NAME).build());
        specDocument.markCompleted();
        specDocumentRepository.saveAndFlush(specDocument);

        project.attachSpecDocument(specDocument.getId());
        projectRepository.saveAndFlush(project);

        FeatureSection section = featureSectionRepository.save(FeatureSection.builder()
                .specDocument(specDocument).title("회원").sourceTitle("3. 회원")
                .displayOrder(0).pageStart(1).pageEnd(2).build());

        Feature feature = featureRepository.save(Feature.builder()
                .specDocument(specDocument).section(section).name("회원가입")
                .displayOrder(0).sourcePageStart(1).sourcePageEnd(1).build());

        // 사용자가 검토까지 마친 상태. 교체하면 이 기록도 함께 사라진다.
        feature.markModified();
        featureRepository.saveAndFlush(feature);

        featureRequirementRepository.save(FeatureRequirement.builder()
                .feature(feature).content("가입한다.").sourceText("원문").displayOrder(0).build());
        featureIssueRepository.save(FeatureIssue.builder()
                .feature(feature).issueType(FeatureIssueType.SOURCE_REVIEW_REQUIRED)
                .description("확인 필요").build());

        return specDocument.getId();
    }

    private static MultipartFile pdfFile() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);

            return new MockMultipartFile(
                    "file", "교체본.pdf", MediaType.APPLICATION_PDF_VALUE, out.toByteArray());
        }
    }

    private SpecDocument reloadCurrent() {
        return specDocumentRepository.findByProjectId(projectId).orElseThrow();
    }

    @Test
    @DisplayName("교체하면 새 문서만 남고 PENDING으로 시작한다")
    void replacesSpecDocument() throws IOException {
        FeatureSpecUploadResponse response = service.replace(projectId, userId, pdfFile());

        assertThat(response.specDocumentId()).isNotEqualTo(currentSpecDocumentId);
        assertThat(response.extractionStatus()).isEqualTo(ExtractionStatus.PENDING);
        assertThat(specDocumentRepository.count()).isEqualTo(1);
        assertThat(specDocumentRepository.findById(currentSpecDocumentId)).isEmpty();
        assertThat(reloadCurrent().getFileName()).isEqualTo("교체본.pdf");
    }

    /**
     * 추출 결과와 검토 기록은 ON DELETE CASCADE로 함께 사라진다. 애플리케이션이 순서를 챙기지
     * 않으므로, 연쇄가 실제로 걸려 있는지는 DB에 물어봐야 알 수 있다.
     */
    @Test
    @DisplayName("이전 추출 결과와 검토 기록이 모두 사라진다")
    void cascadesPreviousResult() throws IOException {
        assertThat(featureRepository.count()).isEqualTo(1);

        service.replace(projectId, userId, pdfFile());

        assertThat(featureSectionRepository.count()).isZero();
        assertThat(featureRepository.count()).isZero();
        assertThat(featureRequirementRepository.count()).isZero();
        assertThat(featureIssueRepository.count()).isZero();
    }

    @Test
    @DisplayName("프로젝트의 활성 명세서 포인터가 새 문서를 가리킨다")
    void movesActiveSpecDocumentPointer() throws IOException {
        FeatureSpecUploadResponse response = service.replace(projectId, userId, pdfFile());

        Project project = projectRepository.findById(projectId).orElseThrow();
        assertThat(project.getActiveSpecDocumentId()).isEqualTo(response.specDocumentId());
    }

    /** 위저드 단계는 앞으로만 간다. 교체가 사용자를 ① 단계로 되돌리면 안 된다. */
    @Test
    @DisplayName("위저드 단계가 되돌아가지 않는다")
    void keepsOnboardingStep() throws IOException {
        Project before = projectRepository.findById(projectId).orElseThrow();
        before.advanceOnboardingStep(ProjectOnboardingStep.ANALYSIS);
        projectRepository.saveAndFlush(before);

        service.replace(projectId, userId, pdfFile());

        assertThat(projectRepository.findById(projectId).orElseThrow().getOnboardingStep())
                .isEqualTo(ProjectOnboardingStep.ANALYSIS);
    }

    @Test
    @DisplayName("분석 중인 문서는 교체할 수 없다")
    void rejectsWhileProcessing() throws IOException {
        SpecDocument current = reloadCurrent();
        ReflectionTestUtils.setField(current, "extractionStatus", ExtractionStatus.PROCESSING);
        specDocumentRepository.saveAndFlush(current);

        MultipartFile file = pdfFile();

        assertThatThrownBy(() -> service.replace(projectId, userId, file))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_EXTRACTION_IN_PROGRESS);

        assertThat(reloadCurrent().getId()).isEqualTo(currentSpecDocumentId);
        assertThat(featureRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("타인 프로젝트의 명세서는 교체할 수 없다")
    void rejectsProjectOwnedByAnotherUser() throws IOException {
        User other = userRepository.save(
                User.ofGithub(System.nanoTime(), "other", "다른 사람", null, null));
        MultipartFile file = pdfFile();

        assertThatThrownBy(() -> service.replace(projectId, other.getId(), file))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);

        assertThat(reloadCurrent().getId()).isEqualTo(currentSpecDocumentId);
    }

    @Test
    @DisplayName("등록된 명세서가 없으면 교체가 아니라 404다")
    void rejectsWhenNothingToReplace() throws IOException {
        User other = userRepository.save(
                User.ofGithub(System.nanoTime(), "empty", "빈 사람", null, null));
        Project empty = projectRepository.save(Project.create(other, "빈 프로젝트"));
        MultipartFile file = pdfFile();

        assertThatThrownBy(() -> service.replace(empty.getId(), other.getId(), file))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_NOT_ACCESSIBLE);
    }

    @Nested
    @DisplayName("동시성 — 더블클릭")
    class Concurrency {

        private static final int THREADS = 4;

        /**
         * 상태 확인과 실제 삭제 사이에는 PDF 파싱이 통째로 들어간다. 그 사이에 먼저 도착한
         * 요청이 커밋되면 늦은 쪽은 이미 사라진 행을 지우게 되는데, 엔티티를 읽어 지우는 방식은
         * Hibernate의 행 수 검사에 걸려 ObjectOptimisticLockingFailureException이 된다.
         *
         * <p>그 예외는 GlobalException이 아니라 핸들러 폴백으로 흘러 500과 error 로그가 된다.
         * 사용자가 버튼을 두 번 누른 것뿐이므로 409여야 한다.
         */
        @RepeatedTest(5)
        @DisplayName("동시에 눌러도 진 쪽은 409다 — 500이 새면 알람까지 울린다")
        void loserGetsConflictNotServerError() throws Exception {
            List<Throwable> failures = new ArrayList<>();
            List<Long> succeeded = new ArrayList<>();

            try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Object>> futures = new ArrayList<>();

                for (int i = 0; i < THREADS; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        try {
                            return service.replace(projectId, userId, pdfFile()).specDocumentId();
                        } catch (Throwable e) {
                            return e;
                        }
                    }));
                }
                start.countDown();

                for (Future<Object> future : futures) {
                    Object result = future.get(30, TimeUnit.SECONDS);
                    if (result instanceof Throwable failure) {
                        failures.add(failure);
                    } else {
                        succeeded.add((Long) result);
                    }
                }
            }

            assertThat(succeeded).hasSize(1);
            assertThat(failures)
                    .allSatisfy(failure -> assertThat(failure).isInstanceOf(ConflictException.class));
            assertThat(specDocumentRepository.count()).isEqualTo(1);
        }
    }
}