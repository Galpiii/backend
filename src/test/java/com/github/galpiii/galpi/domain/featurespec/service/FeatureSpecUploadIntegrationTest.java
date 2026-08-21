package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecUploadResponse;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecFileValidator;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecTempFileStore;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import org.hibernate.exception.ConstraintViolationException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.verify;

@DisplayName("기능명세서 업로드 — 실제 Postgres")
class FeatureSpecUploadIntegrationTest extends IntegrationTestSupport {

    private static final String FILE_NAME = "기능명세서.pdf";

    @Autowired
    private FeatureSpecService service;
    @Autowired
    private SpecDocumentRepository specDocumentRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FeatureSpecTempFileStore tempFileStore;

    @MockitoSpyBean
    private FeatureSpecFileValidator featureSpecFileValidator;

    /**
     * 분석은 업로드가 끝난 뒤 별도 스레드에서 돈다. 이 클래스가 검증하는 것은 업로드까지이고,
     * 그 스레드를 살려 두면 다음 테스트가 DB를 비우는 사이에 상태를 써서 서로를 간섭한다.
     *
     * <p>대신 임시 PDF를 지울 주체가 사라지므로, 실제 분석이 하듯 파일을 지우도록 세워 둔다.
     */
    @MockitoBean
    private FeatureExtractionService featureExtractionService;

    private Long userId;
    private Long projectId;

    @BeforeEach
    void setUp() {
        willAnswer(invocation -> {
            tempFileStore.delete(invocation.getArgument(1, File.class));
            return null;
        }).given(featureExtractionService).extract(anyLong(), any(File.class));

        User user = userRepository.save(
                User.ofGithub(System.nanoTime(), "galpi-tester", "테스터", null, null));
        Project project = projectRepository.save(
                Project.create(user, "갈피"));

        userId = user.getId();
        projectId = project.getId();
    }

    private static MultipartFile pdfFile() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);

            return new MockMultipartFile(
                    "file", FILE_NAME, MediaType.APPLICATION_PDF_VALUE, out.toByteArray());
        }
    }

    /**
     * 제약 이름이 어긋나면 동시 업로드가 409 대신 500으로 나가는데, 애플리케이션 사전 검사가
     * 평소에 다 걸러주기 때문에 그 사실이 드러나지 않는다.
     */
    @Test
    @DisplayName("실제 UNIQUE 위반이 writer가 찾는 제약 이름을 달고 온다")
    void realViolationCarriesTheExpectedConstraintName() {
        Project project = projectRepository.findById(projectId).orElseThrow();
        User user = userRepository.findById(userId).orElseThrow();
        specDocumentRepository.saveAndFlush(SpecDocument.builder()
                .project(project).user(user).fileName(FILE_NAME).build());

        assertThatThrownBy(() -> specDocumentRepository.saveAndFlush(SpecDocument.builder()
                .project(project).user(user).fileName("두번째.pdf").build()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .cause().isInstanceOf(ConstraintViolationException.class)
                .extracting(cause -> ((ConstraintViolationException) cause).getConstraintName())
                .asString()
                .isEqualToIgnoringCase("uk_spec_documents_project");
    }

    @Test
    @DisplayName("업로드하면 PENDING 상태로 실제 행이 생긴다")
    void persistsSpecDocument() throws IOException {
        FeatureSpecUploadResponse response = service.upload(projectId, userId, pdfFile());

        SpecDocument saved = specDocumentRepository.findById(response.specDocumentId()).orElseThrow();
        assertThat(saved.getFileName()).isEqualTo(FILE_NAME);
        assertThat(saved.getExtractionStatus()).isEqualTo(ExtractionStatus.PENDING);
        assertThat(saved.getProject().getId()).isEqualTo(projectId);
        assertThat(saved.getUser().getId()).isEqualTo(userId);
        verify(featureExtractionService).extract(eq(response.specDocumentId()), any(File.class));
    }

    @Test
    @DisplayName("PDF 검증은 트랜잭션 밖에서 일어난다 — 커넥션을 쥔 채 파일을 파싱하면 안 된다")
    void validatesOutsideTransaction() throws IOException {
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        willAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).given(featureSpecFileValidator).validate(any());

        service.upload(projectId, userId, pdfFile());

        assertThat(transactionActive).isFalse();
    }

    @Test
    @DisplayName("같은 프로젝트에 두 번 올리면 두 번째는 거절하고 행은 하나로 유지한다")
    void rejectsSecondUploadForSameProject() throws IOException {
        service.upload(projectId, userId, pdfFile());

        assertThatThrownBy(() -> service.upload(projectId, userId, pdfFile()))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_ALREADY_EXISTS);

        assertThat(specDocumentRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("타인 프로젝트는 실제 조회로 걸러낸다")
    void rejectsProjectOwnedByAnotherUser() throws IOException {
        User other = userRepository.save(
                User.ofGithub(System.nanoTime(), "other", "다른 사람", null, null));

        assertThatThrownBy(() -> service.upload(projectId, other.getId(), pdfFile()))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);

        assertThat(specDocumentRepository.count()).isZero();
    }

    @Test
    @DisplayName("없는 프로젝트는 실제 조회로 걸러낸다")
    void rejectsMissingProject() throws IOException {
        assertThatThrownBy(() -> service.upload(projectId + 1000, userId, pdfFile()))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);

        assertThat(specDocumentRepository.count()).isZero();
    }
}
