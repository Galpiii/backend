package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecUploadResponse;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecFileValidator;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecFileValidator.ValidatedFeatureSpec;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeatureSpecService — 기능명세서 업로드")
class FeatureSpecServiceTest {

    private static final long PROJECT_ID = 1L;
    private static final long USER_ID = 7L;
    private static final long SPEC_DOCUMENT_ID = 10L;
    private static final String FILE_NAME = "기능명세서.pdf";

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private SpecDocumentRepository specDocumentRepository;
    @Mock
    private FeatureSpecFileValidator featureSpecFileValidator;
    @Mock
    private SpecDocumentWriter specDocumentWriter;
    @Mock
    private FeatureExtractionService featureExtractionService;

    @InjectMocks
    private FeatureSpecService service;

    private MultipartFile file;
    private File tempFile;
    private ValidatedFeatureSpec validatedFeatureSpec;

    @BeforeEach
    void setUp() {
        file = new MockMultipartFile(
                "file", FILE_NAME, MediaType.APPLICATION_PDF_VALUE, "pdf".getBytes());
        tempFile = new File("feature-spec-test.pdf");
        validatedFeatureSpec = new ValidatedFeatureSpec(FILE_NAME, tempFile);
    }

    private void givenOwnedProject() {
        given(projectRepository.existsByIdAndUserId(PROJECT_ID, USER_ID)).willReturn(true);
    }

    private void givenUploadableProject() {
        givenOwnedProject();
        given(specDocumentRepository.existsByProjectId(PROJECT_ID)).willReturn(false);
    }

    private SpecDocument specDocument() {
        SpecDocument specDocument = SpecDocument.builder()
                .fileName(FILE_NAME)
                .build();
        ReflectionTestUtils.setField(specDocument, "id", SPEC_DOCUMENT_ID);

        return specDocument;
    }

    @Test
    @DisplayName("업로드에 성공하면 PENDING 상태로 접수 정보를 돌려준다")
    void returnsPendingDocumentOnSuccess() {
        givenUploadableProject();
        given(featureSpecFileValidator.validate(file)).willReturn(validatedFeatureSpec);
        given(specDocumentWriter.save(PROJECT_ID, USER_ID, FILE_NAME)).willReturn(specDocument());

        FeatureSpecUploadResponse response = service.upload(PROJECT_ID, USER_ID, file);

        assertThat(response.specDocumentId()).isEqualTo(SPEC_DOCUMENT_ID);
        assertThat(response.fileName()).isEqualTo(FILE_NAME);
        assertThat(response.extractionStatus()).isEqualTo(ExtractionStatus.PENDING);
    }

    @Test
    @DisplayName("분석을 제출하고, 임시 파일은 지우지 않는다 — 분석이 그대로 이어 쓴다")
    void submitsExtractionAndKeepsTempFile() {
        givenUploadableProject();
        given(featureSpecFileValidator.validate(file)).willReturn(validatedFeatureSpec);
        given(specDocumentWriter.save(PROJECT_ID, USER_ID, FILE_NAME)).willReturn(specDocument());

        service.upload(PROJECT_ID, USER_ID, file);

        verify(featureExtractionService).extract(SPEC_DOCUMENT_ID, tempFile);
        verify(featureSpecFileValidator, never()).deleteTempFile(any());
    }

    @Test
    @DisplayName("제출이 거부되면 접수를 되돌리고 503으로 응답한다 — 재업로드가 막히면 안 된다")
    void failsWhenSubmissionIsRejected() {
        givenUploadableProject();
        given(featureSpecFileValidator.validate(file)).willReturn(validatedFeatureSpec);
        given(specDocumentWriter.save(PROJECT_ID, USER_ID, FILE_NAME)).willReturn(specDocument());
        willThrow(new TaskRejectedException("큐가 가득 찼습니다."))
                .given(featureExtractionService).extract(SPEC_DOCUMENT_ID, tempFile);

        assertThatThrownBy(() -> service.upload(PROJECT_ID, USER_ID, file))
                .isInstanceOf(GlobalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_EXTRACTION_BUSY);

        verify(specDocumentWriter).delete(SPEC_DOCUMENT_ID);
        verify(featureSpecFileValidator).deleteTempFile(tempFile);
    }

    @Test
    @DisplayName("저장이 실패하면 제출하지 않고 임시 파일을 지운다")
    void deletesTempFileWhenSaveFails() {
        givenUploadableProject();
        given(featureSpecFileValidator.validate(file)).willReturn(validatedFeatureSpec);
        given(specDocumentWriter.save(PROJECT_ID, USER_ID, FILE_NAME))
                .willThrow(new DataIntegrityViolationException("constraint violation"));

        assertThatThrownBy(() -> service.upload(PROJECT_ID, USER_ID, file))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(featureSpecFileValidator).deleteTempFile(tempFile);
        verify(featureExtractionService, never()).extract(anyLong(), any());
    }

    @Test
    @DisplayName("타인 프로젝트이거나 없는 프로젝트면 파일을 읽지 않는다")
    void rejectsInaccessibleProjectBeforeTouchingFile() {
        given(projectRepository.existsByIdAndUserId(PROJECT_ID, USER_ID)).willReturn(false);

        assertThatThrownBy(() -> service.upload(PROJECT_ID, USER_ID, file))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_ACCESSIBLE);

        verify(featureSpecFileValidator, never()).validate(any());
        verify(featureExtractionService, never()).extract(anyLong(), any());
    }

    @Test
    @DisplayName("이미 등록된 기능명세서가 있으면 파일을 읽지 않는다")
    void rejectsSecondUploadForSameProject() {
        givenOwnedProject();
        given(specDocumentRepository.existsByProjectId(PROJECT_ID)).willReturn(true);

        assertThatThrownBy(() -> service.upload(PROJECT_ID, USER_ID, file))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_ALREADY_EXISTS);

        verify(featureSpecFileValidator, never()).validate(any());
        verify(specDocumentWriter, never()).save(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("검증에 실패하면 저장하지 않고, 임시 파일 정리도 검증 쪽에 맡긴다")
    void skipsSaveWhenValidationFails() {
        givenUploadableProject();
        given(featureSpecFileValidator.validate(file))
                .willThrow(new BadRequestException(ErrorCode.FEATURE_SPEC_PDF_INVALID));

        assertThatThrownBy(() -> service.upload(PROJECT_ID, USER_ID, file))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_PDF_INVALID);

        verify(specDocumentWriter, never()).save(anyLong(), anyLong(), anyString());
        verify(featureSpecFileValidator, never()).deleteTempFile(any());
    }
}
