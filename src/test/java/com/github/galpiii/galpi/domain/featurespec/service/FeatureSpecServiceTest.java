package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecUploadResponse;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecFileValidator;
import com.github.galpiii.galpi.domain.featurespec.validator.ValidatedFeatureSpec;
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
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeatureSpecService — 기능명세서 업로드")
class FeatureSpecServiceTest {

    private static final long PROJECT_ID = 1L;
    private static final long USER_ID = 7L;
    private static final long SPEC_DOCUMENT_ID = 10L;
    private static final String FILE_NAME = "기능명세서.pdf";
    private static final String STORAGE_KEY = "feature-specs/1/3b62218a-980a-4160-bc4e-f52a38c9806c.pdf";

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private SpecDocumentRepository specDocumentRepository;
    @Mock
    private FeatureSpecFileValidator featureSpecFileValidator;
    @Mock
    private S3Service s3Service;
    @Mock
    private SpecDocumentWriter specDocumentWriter;

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
                .storageKey(STORAGE_KEY)
                .build();
        ReflectionTestUtils.setField(specDocument, "id", SPEC_DOCUMENT_ID);

        return specDocument;
    }

    @Test
    @DisplayName("업로드에 성공하면 PENDING 상태로 접수 정보를 돌려준다")
    void returnsPendingDocumentOnSuccess() {
        givenUploadableProject();
        given(featureSpecFileValidator.validate(file)).willReturn(validatedFeatureSpec);
        given(s3Service.upload(tempFile, PROJECT_ID)).willReturn(STORAGE_KEY);
        given(specDocumentWriter.save(PROJECT_ID, USER_ID, FILE_NAME, STORAGE_KEY))
                .willReturn(specDocument());

        FeatureSpecUploadResponse response = service.upload(PROJECT_ID, USER_ID, file);

        assertThat(response.specDocumentId()).isEqualTo(SPEC_DOCUMENT_ID);
        assertThat(response.fileName()).isEqualTo(FILE_NAME);
        assertThat(response.extractionStatus()).isEqualTo(ExtractionStatus.PENDING);
    }

    @Test
    @DisplayName("원본을 올린 뒤에 저장한다 — 순서가 뒤집히면 보상 삭제할 키를 알 수 없다")
    void uploadsBeforeSaving() {
        givenUploadableProject();
        given(featureSpecFileValidator.validate(file)).willReturn(validatedFeatureSpec);
        given(s3Service.upload(tempFile, PROJECT_ID)).willReturn(STORAGE_KEY);
        given(specDocumentWriter.save(PROJECT_ID, USER_ID, FILE_NAME, STORAGE_KEY))
                .willReturn(specDocument());

        service.upload(PROJECT_ID, USER_ID, file);

        InOrder order = inOrder(s3Service, specDocumentWriter);
        order.verify(s3Service).upload(tempFile, PROJECT_ID);
        order.verify(specDocumentWriter).save(PROJECT_ID, USER_ID, FILE_NAME, STORAGE_KEY);
    }

    @Test
    @DisplayName("성공해도 임시 파일을 지운다")
    void deletesTempFileOnSuccess() {
        givenUploadableProject();
        given(featureSpecFileValidator.validate(file)).willReturn(validatedFeatureSpec);
        given(s3Service.upload(tempFile, PROJECT_ID)).willReturn(STORAGE_KEY);
        given(specDocumentWriter.save(PROJECT_ID, USER_ID, FILE_NAME, STORAGE_KEY))
                .willReturn(specDocument());

        service.upload(PROJECT_ID, USER_ID, file);

        verify(featureSpecFileValidator).deleteTempFile(tempFile);
    }

    @Test
    @DisplayName("타인 프로젝트이거나 없는 프로젝트면 파일을 읽지도 올리지도 않는다")
    void rejectsInaccessibleProjectBeforeTouchingFile() {
        given(projectRepository.existsByIdAndUserId(PROJECT_ID, USER_ID)).willReturn(false);

        assertThatThrownBy(() -> service.upload(PROJECT_ID, USER_ID, file))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_ACCESSIBLE);

        verify(featureSpecFileValidator, never()).validate(any());
        verify(s3Service, never()).upload(any(), anyLong());
    }

    @Test
    @DisplayName("이미 등록된 기능명세서가 있으면 파일을 읽지도 올리지도 않는다")
    void rejectsSecondUploadForSameProject() {
        givenOwnedProject();
        given(specDocumentRepository.existsByProjectId(PROJECT_ID)).willReturn(true);

        assertThatThrownBy(() -> service.upload(PROJECT_ID, USER_ID, file))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_ALREADY_EXISTS);

        verify(featureSpecFileValidator, never()).validate(any());
        verify(s3Service, never()).upload(any(), anyLong());
    }

    @Test
    @DisplayName("검증에 실패하면 올리지 않고, 임시 파일 정리도 검증 쪽에 맡긴다")
    void skipsUploadWhenValidationFails() {
        givenUploadableProject();
        given(featureSpecFileValidator.validate(file))
                .willThrow(new BadRequestException(ErrorCode.FEATURE_SPEC_PDF_INVALID));

        assertThatThrownBy(() -> service.upload(PROJECT_ID, USER_ID, file))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_PDF_INVALID);

        verify(s3Service, never()).upload(any(), anyLong());
        verify(featureSpecFileValidator, never()).deleteTempFile(any());
    }

    @Test
    @DisplayName("업로드가 실패하면 저장하지 않고 임시 파일을 지운다")
    void deletesTempFileWhenUploadFails() {
        givenUploadableProject();
        given(featureSpecFileValidator.validate(file)).willReturn(validatedFeatureSpec);
        given(s3Service.upload(tempFile, PROJECT_ID))
                .willThrow(new GlobalException(ErrorCode.FEATURE_SPEC_STORAGE_UPLOAD_FAILED));

        assertThatThrownBy(() -> service.upload(PROJECT_ID, USER_ID, file))
                .isInstanceOf(GlobalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_STORAGE_UPLOAD_FAILED);

        verify(specDocumentWriter, never()).save(anyLong(), anyLong(), anyString(), anyString());
        verify(featureSpecFileValidator).deleteTempFile(tempFile);
    }

    @Test
    @DisplayName("저장이 실패하면 방금 올린 원본을 지운다 — 아무도 참조하지 않는 파일이 남으면 안 된다")
    void deletesStorageObjectWhenSaveFails() {
        givenUploadableProject();
        given(featureSpecFileValidator.validate(file)).willReturn(validatedFeatureSpec);
        given(s3Service.upload(tempFile, PROJECT_ID)).willReturn(STORAGE_KEY);
        given(specDocumentWriter.save(PROJECT_ID, USER_ID, FILE_NAME, STORAGE_KEY))
                .willThrow(new DataIntegrityViolationException("constraint violation"));

        assertThatThrownBy(() -> service.upload(PROJECT_ID, USER_ID, file))
                .isInstanceOf(GlobalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_SAVE_FAILED);

        verify(s3Service).delete(STORAGE_KEY);
        verify(featureSpecFileValidator).deleteTempFile(tempFile);
    }

    @Test
    @DisplayName("보상 삭제까지 실패해도 저장 실패를 그대로 알린다 — 삭제 실패가 원인을 가리면 안 된다")
    void keepsSaveFailureWhenCompensationFails() {
        givenUploadableProject();
        given(featureSpecFileValidator.validate(file)).willReturn(validatedFeatureSpec);
        given(s3Service.upload(tempFile, PROJECT_ID)).willReturn(STORAGE_KEY);
        given(specDocumentWriter.save(PROJECT_ID, USER_ID, FILE_NAME, STORAGE_KEY))
                .willThrow(new DataIntegrityViolationException("constraint violation"));
        willThrow(new IllegalStateException("s3 down")).given(s3Service).delete(STORAGE_KEY);

        assertThatThrownBy(() -> service.upload(PROJECT_ID, USER_ID, file))
                .isInstanceOf(GlobalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_SAVE_FAILED);

        verify(featureSpecFileValidator).deleteTempFile(tempFile);
    }
}
