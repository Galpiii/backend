package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecUploadResponse;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@DisplayName("기능명세서 업로드 — 실제 Postgres")
class FeatureSpecUploadIntegrationTest extends IntegrationTestSupport {

    private static final String FILE_NAME = "기능명세서.pdf";
    private static final String STORAGE_KEY = "feature-specs/1/3b62218a-980a-4160-bc4e-f52a38c9806c.pdf";

    @Autowired
    private FeatureSpecService service;
    @Autowired
    private SpecDocumentRepository specDocumentRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private S3Service s3Service;

    private Long userId;
    private Long projectId;

    @BeforeEach
    void setUp() {
        specDocumentRepository.deleteAllInBatch();
        projectRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        User user = userRepository.save(
                User.ofGithub(System.nanoTime(), "galpi-tester", "테스터", null, null));
        Project project = projectRepository.save(
                Project.builder().name("갈피").user(user).build());

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

    @Test
    @DisplayName("업로드하면 PENDING 상태로 실제 행이 생긴다")
    void persistsSpecDocument() throws IOException {
        given(s3Service.upload(any(), anyLong())).willReturn(STORAGE_KEY);

        FeatureSpecUploadResponse response = service.upload(projectId, userId, pdfFile());

        SpecDocument saved = specDocumentRepository.findById(response.specDocumentId()).orElseThrow();
        assertThat(saved.getFileName()).isEqualTo(FILE_NAME);
        assertThat(saved.getStorageKey()).isEqualTo(STORAGE_KEY);
        assertThat(saved.getExtractionStatus()).isEqualTo(ExtractionStatus.PENDING);
        assertThat(saved.getProject().getId()).isEqualTo(projectId);
        assertThat(saved.getUser().getId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("S3 업로드는 트랜잭션 밖에서 일어난다 — 커넥션을 쥔 채 네트워크를 기다리면 안 된다")
    void uploadsOutsideTransaction() throws IOException {
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        given(s3Service.upload(any(), anyLong())).willAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return STORAGE_KEY;
        });

        service.upload(projectId, userId, pdfFile());

        assertThat(transactionActive).isFalse();
    }

    @Test
    @DisplayName("같은 프로젝트에 두 번 올리면 두 번째는 거절하고 행은 하나로 유지한다")
    void rejectsSecondUploadForSameProject() throws IOException {
        given(s3Service.upload(any(), anyLong())).willReturn(STORAGE_KEY);
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
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_ACCESSIBLE);

        assertThat(specDocumentRepository.count()).isZero();
    }

    @Test
    @DisplayName("없는 프로젝트는 실제 조회로 걸러낸다")
    void rejectsMissingProject() throws IOException {
        assertThatThrownBy(() -> service.upload(projectId + 1000, userId, pdfFile()))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_ACCESSIBLE);

        assertThat(specDocumentRepository.count()).isZero();
    }

    @Test
    @DisplayName("DB 제약에 걸려 저장이 실패하면 올린 원본을 지운다")
    void deletesStorageObjectWhenDatabaseRejectsRow() throws IOException {
        String tooLongKey = "feature-specs/1/" + "a".repeat(600) + ".pdf";
        given(s3Service.upload(any(), anyLong())).willReturn(tooLongKey);

        assertThatThrownBy(() -> service.upload(projectId, userId, pdfFile()))
                .isInstanceOf(GlobalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_SAVE_FAILED);

        verify(s3Service).delete(tooLongKey);
        assertThat(specDocumentRepository.count()).isZero();
    }
}
