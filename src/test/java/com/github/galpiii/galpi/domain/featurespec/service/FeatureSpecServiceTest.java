package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecUploadResponse;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecFileValidator;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecTempFileStore;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecFileValidator.ValidatedFeatureSpec;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeatureSpecService — 기능명세서 업로드와 교체")
class FeatureSpecServiceTest {

    private static final long PROJECT_ID = 1L;
    private static final long USER_ID = 7L;
    private static final long SPEC_DOCUMENT_ID = 10L;
    private static final String FILE_NAME = "기능명세서.pdf";
    private static final long CURRENT_SPEC_DOCUMENT_ID = 9L;

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private SpecDocumentRepository specDocumentRepository;
    @Mock
    private FeatureSpecFileValidator featureSpecFileValidator;
    @Mock
    private FeatureSpecTempFileStore tempFileStore;
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
        given(projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(PROJECT_ID, USER_ID))
                .willReturn(Optional.of(mock(Project.class)));
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
    @DisplayName("분석 큐가 꽉 차면 PDF를 파싱하기 전에 거절한다")
    void rejectsBeforeParsingWhenQueueIsFull() {
        givenUploadableProject();
        given(featureExtractionService.isBusy()).willReturn(true);

        assertThatThrownBy(() -> service.upload(PROJECT_ID, USER_ID, file))
                .isInstanceOf(GlobalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_EXTRACTION_BUSY);

        verify(featureSpecFileValidator, never()).validate(any());
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
        verify(tempFileStore, never()).delete(any());
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
        verify(tempFileStore).delete(tempFile);
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

        verify(tempFileStore).delete(tempFile);
        verify(featureExtractionService, never()).extract(anyLong(), any());
    }

    @Test
    @DisplayName("타인 프로젝트이거나 없는 프로젝트면 파일을 읽지 않는다")
    void rejectsInaccessibleProjectBeforeTouchingFile() {
        given(projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(PROJECT_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(PROJECT_ID, USER_ID, file))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);

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
        verify(tempFileStore, never()).delete(any());
    }

    private SpecDocument specDocument(long id, ExtractionStatus status) {
        SpecDocument specDocument = SpecDocument.builder().fileName(FILE_NAME).build();
        ReflectionTestUtils.setField(specDocument, "id", id);
        ReflectionTestUtils.setField(specDocument, "extractionStatus", status);

        return specDocument;
    }

    private void givenReplaceableProject(ExtractionStatus status) {
        givenOwnedProject();
        given(specDocumentRepository.findByProjectId(PROJECT_ID))
                .willReturn(Optional.of(specDocument(CURRENT_SPEC_DOCUMENT_ID, status)));
    }

    @Nested
    @DisplayName("기능명세서 교체")
    class Replace {

        @Test
        @DisplayName("분석이 끝난 명세서는 교체하고 새 문서로 분석을 제출한다")
        void replacesCompletedSpecDocument() {
            givenReplaceableProject(ExtractionStatus.COMPLETED);
            given(featureSpecFileValidator.validate(file)).willReturn(validatedFeatureSpec);
            given(specDocumentWriter.replace(PROJECT_ID, USER_ID, CURRENT_SPEC_DOCUMENT_ID, FILE_NAME))
                    .willReturn(specDocument());

            FeatureSpecUploadResponse response = service.replace(PROJECT_ID, USER_ID, file);

            assertThat(response.specDocumentId()).isEqualTo(SPEC_DOCUMENT_ID);
            assertThat(response.extractionStatus()).isEqualTo(ExtractionStatus.PENDING);
            verify(featureExtractionService).extract(SPEC_DOCUMENT_ID, tempFile);
            verify(tempFileStore, never()).delete(any());
        }

        @Test
        @DisplayName("실패한 명세서도 교체할 수 있다")
        void replacesFailedSpecDocument() {
            givenReplaceableProject(ExtractionStatus.FAILED);
            given(featureSpecFileValidator.validate(file)).willReturn(validatedFeatureSpec);
            given(specDocumentWriter.replace(PROJECT_ID, USER_ID, CURRENT_SPEC_DOCUMENT_ID, FILE_NAME))
                    .willReturn(specDocument());

            service.replace(PROJECT_ID, USER_ID, file);

            verify(featureExtractionService).extract(SPEC_DOCUMENT_ID, tempFile);
        }

        /** 실수로 두 번 누르면 두 번째 요청은 갓 만들어진 PENDING 문서를 보고 여기서 걸린다. */
        @Test
        @DisplayName("분석 대기 중이면 409로 막고 파일을 읽지 않는다")
        void rejectsWhilePending() {
            givenReplaceableProject(ExtractionStatus.PENDING);

            assertThatThrownBy(() -> service.replace(PROJECT_ID, USER_ID, file))
                    .isInstanceOf(ConflictException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_EXTRACTION_IN_PROGRESS);

            verify(featureSpecFileValidator, never()).validate(any());
            verify(specDocumentWriter, never()).replace(anyLong(), anyLong(), anyLong(), anyString());
        }

        @Test
        @DisplayName("분석 진행 중이면 409로 막는다 — 결과를 저장할 자리가 사라진다")
        void rejectsWhileProcessing() {
            givenReplaceableProject(ExtractionStatus.PROCESSING);

            assertThatThrownBy(() -> service.replace(PROJECT_ID, USER_ID, file))
                    .isInstanceOf(ConflictException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_EXTRACTION_IN_PROGRESS);

            verify(specDocumentWriter, never()).replace(anyLong(), anyLong(), anyLong(), anyString());
        }

        @Test
        @DisplayName("등록된 명세서가 없으면 404다 — 교체가 아니라 업로드를 써야 한다")
        void rejectsWhenNothingToReplace() {
            givenOwnedProject();
            given(specDocumentRepository.findByProjectId(PROJECT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.replace(PROJECT_ID, USER_ID, file))
                    .isInstanceOf(NotFoundException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_NOT_ACCESSIBLE);

            verify(featureSpecFileValidator, never()).validate(any());
        }

        @Test
        @DisplayName("타인 프로젝트면 명세서를 조회하지도 않는다")
        void rejectsInaccessibleProject() {
            given(projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(PROJECT_ID, USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.replace(PROJECT_ID, USER_ID, file))
                    .isInstanceOf(NotFoundException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);

            verify(specDocumentRepository, never()).findByProjectId(anyLong());
            verify(featureSpecFileValidator, never()).validate(any());
        }

        @Test
        @DisplayName("분석 큐가 꽉 차면 PDF를 파싱하기 전에 거절한다")
        void rejectsBeforeParsingWhenQueueIsFull() {
            givenReplaceableProject(ExtractionStatus.COMPLETED);
            given(featureExtractionService.isBusy()).willReturn(true);

            assertThatThrownBy(() -> service.replace(PROJECT_ID, USER_ID, file))
                    .isInstanceOf(GlobalException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_EXTRACTION_BUSY);

            verify(featureSpecFileValidator, never()).validate(any());
        }

        /**
         * PDF 파싱이 수백 ms를 쓰는 사이 큐가 찰 수 있다. 지우기 전에 걸러내면 기존 명세서가
         * 그대로 남고, 안내도 삭제되지 않았다는 001이 나간다.
         */
        @Test
        @DisplayName("파싱 도중 큐가 차면 지우기 전에 막는다 — 기존 명세서가 남는다")
        void rejectsBeforeDeletingWhenQueueFillsDuringParsing() {
            givenReplaceableProject(ExtractionStatus.COMPLETED);
            given(featureSpecFileValidator.validate(file)).willReturn(validatedFeatureSpec);
            given(featureExtractionService.isBusy()).willReturn(false, true);

            assertThatThrownBy(() -> service.replace(PROJECT_ID, USER_ID, file))
                    .isInstanceOf(GlobalException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_EXTRACTION_BUSY);

            verify(specDocumentWriter, never()).replace(anyLong(), anyLong(), anyLong(), anyString());
            verify(tempFileStore).delete(tempFile);
        }

        @Test
        @DisplayName("교체 저장이 실패하면 임시 파일을 지운다")
        void deletesTempFileWhenReplaceFails() {
            givenReplaceableProject(ExtractionStatus.COMPLETED);
            given(featureSpecFileValidator.validate(file)).willReturn(validatedFeatureSpec);
            given(specDocumentWriter.replace(PROJECT_ID, USER_ID, CURRENT_SPEC_DOCUMENT_ID, FILE_NAME))
                    .willThrow(new ConflictException(ErrorCode.FEATURE_SPEC_ALREADY_EXISTS));

            assertThatThrownBy(() -> service.replace(PROJECT_ID, USER_ID, file))
                    .isInstanceOf(ConflictException.class);

            verify(tempFileStore).delete(tempFile);
            verify(featureExtractionService, never()).extract(anyLong(), any());
        }

        /**
         * 동시에 도착한 더블클릭은 상태 확인을 나란히 통과한다. 늦은 쪽의 삭제가 0건이 되는데,
         * 이것이 500으로 새면 알람까지 울린다.
         */
        @Test
        @DisplayName("동시 교체로 지울 문서가 사라지면 409로 갈린다 — 500이 되면 안 된다")
        void mapsLostRaceToConflict() {
            givenReplaceableProject(ExtractionStatus.COMPLETED);
            given(featureSpecFileValidator.validate(file)).willReturn(validatedFeatureSpec);
            given(specDocumentWriter.replace(PROJECT_ID, USER_ID, CURRENT_SPEC_DOCUMENT_ID, FILE_NAME))
                    .willThrow(new ConflictException(ErrorCode.FEATURE_SPEC_EXTRACTION_IN_PROGRESS));

            assertThatThrownBy(() -> service.replace(PROJECT_ID, USER_ID, file))
                    .isInstanceOf(ConflictException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_EXTRACTION_IN_PROGRESS);

            verify(tempFileStore).delete(tempFile);
            verify(featureExtractionService, never()).extract(anyLong(), any());
        }

        /** 기존 문서를 이미 지운 뒤라 되돌릴 원본이 없다. 업로드와 다른 코드로 그 사실을 알린다. */
        @Test
        @DisplayName("제출이 거부되면 새 문서를 지우고 교체 전용 503으로 응답한다")
        void failsWithReplaceSpecificCodeWhenSubmissionIsRejected() {
            givenReplaceableProject(ExtractionStatus.COMPLETED);
            given(featureSpecFileValidator.validate(file)).willReturn(validatedFeatureSpec);
            given(specDocumentWriter.replace(PROJECT_ID, USER_ID, CURRENT_SPEC_DOCUMENT_ID, FILE_NAME))
                    .willReturn(specDocument());
            willThrow(new TaskRejectedException("큐가 가득 찼습니다."))
                    .given(featureExtractionService).extract(SPEC_DOCUMENT_ID, tempFile);

            assertThatThrownBy(() -> service.replace(PROJECT_ID, USER_ID, file))
                    .isInstanceOf(GlobalException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_REPLACE_BUSY);

            verify(specDocumentWriter).delete(SPEC_DOCUMENT_ID);
            verify(tempFileStore).delete(tempFile);
        }
    }
}
