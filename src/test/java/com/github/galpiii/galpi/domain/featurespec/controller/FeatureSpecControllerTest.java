package com.github.galpiii.galpi.domain.featurespec.controller;

import com.github.galpiii.galpi.domain.auth.jwt.JwtTokenProvider;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecStatusResponse;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecUploadResponse;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionFailureCode;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionStatus;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import com.github.galpiii.galpi.support.WebMvcTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("FeatureSpecController — 기능명세서")
class FeatureSpecControllerTest extends WebMvcTestSupport {

    private static final long PROJECT_ID = 1L;
    private static final long USER_ID = 7L;
    private static final long SPEC_DOCUMENT_ID = 10L;
    private static final String FILE_NAME = "기능명세서.pdf";
    private static final String UPLOAD_PATH = "/projects/{projectId}/feature-specs";
    private static final String STATUS_PATH = "/projects/{projectId}/feature-specs/{specDocumentId}/status";

    @Autowired
    private JwtTokenProvider tokenProvider;

    private String bearer() {
        return "Bearer " + tokenProvider.createAccessToken(USER_ID);
    }

    private MockMultipartFile pdfPart() {
        return new MockMultipartFile(
                "file", FILE_NAME, MediaType.APPLICATION_PDF_VALUE, "pdf".getBytes());
    }

    @Nested
    @DisplayName("기능명세서 업로드 — POST /projects/{projectId}/feature-specs")
    class Upload {

        @Test
        @DisplayName("업로드에 성공하면 201과 접수 정보를 내려준다")
        void returnsCreatedOnSuccess() throws Exception {
            given(featureSpecService.upload(eq(PROJECT_ID), eq(USER_ID), any()))
                    .willReturn(new FeatureSpecUploadResponse(
                            SPEC_DOCUMENT_ID, FILE_NAME, ExtractionStatus.PENDING));

            mockMvc.perform(multipart(UPLOAD_PATH, PROJECT_ID)
                            .file(pdfPart())
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.specDocumentId").value(SPEC_DOCUMENT_ID))
                    .andExpect(jsonPath("$.data.fileName").value(FILE_NAME))
                    .andExpect(jsonPath("$.data.extractionStatus").value("PENDING"));
        }

        @Test
        @DisplayName("인증된 사용자 id를 서비스로 넘긴다")
        void passesAuthenticatedUserId() throws Exception {
            given(featureSpecService.upload(eq(PROJECT_ID), eq(USER_ID), any()))
                    .willReturn(new FeatureSpecUploadResponse(
                            SPEC_DOCUMENT_ID, FILE_NAME, ExtractionStatus.PENDING));

            mockMvc.perform(multipart(UPLOAD_PATH, PROJECT_ID)
                            .file(pdfPart())
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isCreated());

            verify(featureSpecService).upload(eq(PROJECT_ID), eq(USER_ID), any());
        }

        @Test
        @DisplayName("인증 없이 부르면 401이고 서비스를 호출하지 않는다")
        void rejectsUnauthenticatedRequest() throws Exception {
            mockMvc.perform(multipart(UPLOAD_PATH, PROJECT_ID)
                            .file(pdfPart()))
                    .andExpect(status().isUnauthorized());

            verify(featureSpecService, never()).upload(any(), any(), any());
        }

        @Test
        @DisplayName("file 파트가 없으면 400이다")
        void rejectsMissingFilePart() throws Exception {
            mockMvc.perform(multipart(UPLOAD_PATH, PROJECT_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.MISSING_REQUEST_VALUE.getCode()));
        }

        @Test
        @DisplayName("접근할 수 없는 프로젝트면 404 PROJECT-002를 내려준다")
        void returnsNotFoundWhenProjectInaccessible() throws Exception {
            willThrow(new NotFoundException(ErrorCode.PROJECT_NOT_FOUND))
                    .given(featureSpecService).upload(eq(PROJECT_ID), eq(USER_ID), any());

            mockMvc.perform(multipart(UPLOAD_PATH, PROJECT_ID)
                            .file(pdfPart())
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(ErrorCode.PROJECT_NOT_FOUND.getCode()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("이미 등록된 기능명세서가 있으면 409를 내려준다")
        void returnsConflictWhenSpecAlreadyExists() throws Exception {
            willThrow(new ConflictException(ErrorCode.FEATURE_SPEC_ALREADY_EXISTS))
                    .given(featureSpecService).upload(eq(PROJECT_ID), eq(USER_ID), any());

            mockMvc.perform(multipart(UPLOAD_PATH, PROJECT_ID)
                            .file(pdfPart())
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(ErrorCode.FEATURE_SPEC_ALREADY_EXISTS.getCode()));
        }

        @Test
        @DisplayName("파일 검증에 실패하면 400과 해당 코드를 내려준다")
        void returnsBadRequestWhenValidationFails() throws Exception {
            willThrow(new BadRequestException(ErrorCode.FEATURE_SPEC_PDF_ENCRYPTED))
                    .given(featureSpecService).upload(eq(PROJECT_ID), eq(USER_ID), any());

            mockMvc.perform(multipart(UPLOAD_PATH, PROJECT_ID)
                            .file(pdfPart())
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.FEATURE_SPEC_PDF_ENCRYPTED.getCode()));
        }
    }

    @Nested
    @DisplayName("분석 상태 조회 — GET /projects/{projectId}/feature-specs/{specDocumentId}/status")
    class Status {

        @Test
        @DisplayName("분석 중이면 200과 현재 상태를 내려준다")
        void returnsCurrentStatus() throws Exception {
            given(featureSpecService.getStatus(PROJECT_ID, SPEC_DOCUMENT_ID, USER_ID))
                    .willReturn(new FeatureSpecStatusResponse(
                            SPEC_DOCUMENT_ID, ExtractionStatus.PROCESSING, null));

            mockMvc.perform(get(STATUS_PATH, PROJECT_ID, SPEC_DOCUMENT_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.specDocumentId").value(SPEC_DOCUMENT_ID))
                    .andExpect(jsonPath("$.data.extractionStatus").value("PROCESSING"));
        }

        @Test
        @DisplayName("실패가 아니면 failureCode를 아예 내려보내지 않는다")
        void omitsFailureCodeWhenNotFailed() throws Exception {
            given(featureSpecService.getStatus(PROJECT_ID, SPEC_DOCUMENT_ID, USER_ID))
                    .willReturn(new FeatureSpecStatusResponse(
                            SPEC_DOCUMENT_ID, ExtractionStatus.COMPLETED, null));

            mockMvc.perform(get(STATUS_PATH, PROJECT_ID, SPEC_DOCUMENT_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.failureCode").value(nullValue()));
        }

        @Test
        @DisplayName("실패했으면 사유까지 함께 내려준다")
        void returnsFailureCodeWhenFailed() throws Exception {
            given(featureSpecService.getStatus(PROJECT_ID, SPEC_DOCUMENT_ID, USER_ID))
                    .willReturn(new FeatureSpecStatusResponse(
                            SPEC_DOCUMENT_ID,
                            ExtractionStatus.FAILED,
                            ExtractionFailureCode.NO_FEATURE_EXTRACTED));

            mockMvc.perform(get(STATUS_PATH, PROJECT_ID, SPEC_DOCUMENT_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.extractionStatus").value("FAILED"))
                    .andExpect(jsonPath("$.data.failureCode").value("NO_FEATURE_EXTRACTED"));
        }

        @Test
        @DisplayName("인증 없이 부르면 401이고 서비스를 호출하지 않는다")
        void rejectsUnauthenticatedRequest() throws Exception {
            mockMvc.perform(get(STATUS_PATH, PROJECT_ID, SPEC_DOCUMENT_ID))
                    .andExpect(status().isUnauthorized());

            verify(featureSpecService, never()).getStatus(any(), any(), any());
        }

        @Test
        @DisplayName("접근할 수 없는 기능명세서면 404 FEATURE-SPEC-ACCESS-001을 내려준다")
        void returnsNotFoundWhenSpecInaccessible() throws Exception {
            willThrow(new NotFoundException(ErrorCode.FEATURE_SPEC_NOT_ACCESSIBLE))
                    .given(featureSpecService).getStatus(PROJECT_ID, SPEC_DOCUMENT_ID, USER_ID);

            mockMvc.perform(get(STATUS_PATH, PROJECT_ID, SPEC_DOCUMENT_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(ErrorCode.FEATURE_SPEC_NOT_ACCESSIBLE.getCode()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }
}
