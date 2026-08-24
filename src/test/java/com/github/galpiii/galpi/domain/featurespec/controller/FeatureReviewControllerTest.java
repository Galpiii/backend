package com.github.galpiii.galpi.domain.featurespec.controller;

import com.github.galpiii.galpi.domain.auth.jwt.JwtTokenProvider;
import com.github.galpiii.galpi.domain.featurespec.dto.FeatureReviewFilter;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureReviewResponse;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureReviewSummaryResponse;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("FeatureReviewController — 기능 검토")
class FeatureReviewControllerTest extends WebMvcTestSupport {

    private static final long PROJECT_ID = 1L;
    private static final long USER_ID = 7L;
    private static final long SPEC_DOCUMENT_ID = 10L;
    private static final long FEATURE_ID = 12L;

    private static final String BASE = "/projects/{projectId}/feature-specs/{specDocumentId}";
    private static final String FEATURES_PATH = BASE + "/features";
    private static final String SUMMARY_PATH = BASE + "/review-summary";
    private static final String CONFIRM_ALL_PATH = BASE + "/features/confirm-all";
    private static final String FEATURE_PATH = BASE + "/features/{featureId}";

    @Autowired
    private JwtTokenProvider tokenProvider;

    private String bearer() {
        return "Bearer " + tokenProvider.createAccessToken(USER_ID);
    }

    @Nested
    @DisplayName("기능 목록 조회 — GET /features")
    class ListFeatures {

        @Test
        @DisplayName("분류별로 묶인 목록을 200으로 내려준다")
        void returnsGroupedFeatures() throws Exception {
            FeatureReviewResponse.Feature feature = new FeatureReviewResponse.Feature(
                    FEATURE_ID, "게시글 작성", FeatureReviewStatus.UNREVIEWED, 3, 4,
                    List.of(new FeatureReviewResponse.Requirement(34L, "게시글을 작성한다.", "원문")),
                    List.of(), List.of(), List.of());

            given(featureReviewService.list(PROJECT_ID, SPEC_DOCUMENT_ID, USER_ID, null))
                    .willReturn(new FeatureReviewResponse(List.of(
                            new FeatureReviewResponse.SectionGroup(3L, "게시글", List.of(feature)))));

            mockMvc.perform(get(FEATURES_PATH, PROJECT_ID, SPEC_DOCUMENT_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sections[0].sectionId").value(3))
                    .andExpect(jsonPath("$.data.sections[0].title").value("게시글"))
                    .andExpect(jsonPath("$.data.sections[0].features[0].featureId").value(FEATURE_ID))
                    .andExpect(jsonPath("$.data.sections[0].features[0].reviewStatus").value("UNREVIEWED"))
                    .andExpect(jsonPath("$.data.sections[0].features[0].requirements[0].requirementId").value(34));
        }

        @Test
        @DisplayName("filter를 그대로 서비스에 넘긴다")
        void passesFilter() throws Exception {
            given(featureReviewService.list(PROJECT_ID, SPEC_DOCUMENT_ID, USER_ID,
                    FeatureReviewFilter.REVIEW_REQUIRED))
                    .willReturn(new FeatureReviewResponse(List.of()));

            mockMvc.perform(get(FEATURES_PATH, PROJECT_ID, SPEC_DOCUMENT_ID)
                            .param("filter", "REVIEW_REQUIRED")
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isOk());

            verify(featureReviewService).list(
                    PROJECT_ID, SPEC_DOCUMENT_ID, USER_ID, FeatureReviewFilter.REVIEW_REQUIRED);
        }

        @Test
        @DisplayName("알 수 없는 filter 값이면 400이다")
        void rejectsUnknownFilter() throws Exception {
            mockMvc.perform(get(FEATURES_PATH, PROJECT_ID, SPEC_DOCUMENT_ID)
                            .param("filter", "무엇인가")
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_TYPE_VALUE.getCode()));
        }

        @Test
        @DisplayName("인증 없이 부르면 401이고 서비스를 호출하지 않는다")
        void requiresAuthentication() throws Exception {
            mockMvc.perform(get(FEATURES_PATH, PROJECT_ID, SPEC_DOCUMENT_ID))
                    .andExpect(status().isUnauthorized());

            verify(featureReviewService, never()).list(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("검토 요약 — GET /review-summary")
    class Summary {

        @Test
        @DisplayName("탭별 개수를 200으로 내려준다")
        void returnsSummary() throws Exception {
            given(featureReviewService.summary(PROJECT_ID, SPEC_DOCUMENT_ID, USER_ID))
                    .willReturn(new FeatureReviewSummaryResponse(5, 7, 25, 37));

            mockMvc.perform(get(SUMMARY_PATH, PROJECT_ID, SPEC_DOCUMENT_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reviewRequired").value(5))
                    .andExpect(jsonPath("$.data.noIssue").value(7))
                    .andExpect(jsonPath("$.data.reviewed").value(25))
                    .andExpect(jsonPath("$.data.total").value(37));
        }

        @Test
        @DisplayName("접근할 수 없는 기능명세서면 404다")
        void returnsNotFound() throws Exception {
            willThrow(new NotFoundException(ErrorCode.FEATURE_SPEC_NOT_ACCESSIBLE))
                    .given(featureReviewService).summary(PROJECT_ID, SPEC_DOCUMENT_ID, USER_ID);

            mockMvc.perform(get(SUMMARY_PATH, PROJECT_ID, SPEC_DOCUMENT_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(ErrorCode.FEATURE_SPEC_NOT_ACCESSIBLE.getCode()));
        }
    }

    @Nested
    @DisplayName("기능 수정 — PATCH /features/{featureId}")
    class Update {

        @Test
        @DisplayName("수정된 기능을 200으로 내려준다")
        void returnsUpdatedFeature() throws Exception {
            given(featureReviewService.update(eq(PROJECT_ID), eq(SPEC_DOCUMENT_ID), eq(USER_ID),
                    eq(FEATURE_ID), any()))
                    .willReturn(new FeatureReviewResponse.Feature(
                            FEATURE_ID, "게시글 관리", FeatureReviewStatus.USER_MODIFIED, 3, 4,
                            List.of(), List.of(), List.of(), List.of()));

            mockMvc.perform(patch(FEATURE_PATH, PROJECT_ID, SPEC_DOCUMENT_ID, FEATURE_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "게시글 관리"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("게시글 관리"))
                    .andExpect(jsonPath("$.data.reviewStatus").value("USER_MODIFIED"));
        }

        @Test
        @DisplayName("요구사항 내용이 비어 있으면 400이고 서비스를 호출하지 않는다")
        void rejectsBlankRequirement() throws Exception {
            mockMvc.perform(patch(FEATURE_PATH, PROJECT_ID, SPEC_DOCUMENT_ID, FEATURE_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requirements": [{"id": null, "content": "   "}]}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

            verify(featureReviewService, never())
                    .update(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("남의 기능이면 404다")
        void returnsNotFound() throws Exception {
            willThrow(new NotFoundException(ErrorCode.FEATURE_NOT_ACCESSIBLE))
                    .given(featureReviewService).update(eq(PROJECT_ID), eq(SPEC_DOCUMENT_ID),
                            eq(USER_ID), eq(FEATURE_ID), any());

            mockMvc.perform(patch(FEATURE_PATH, PROJECT_ID, SPEC_DOCUMENT_ID, FEATURE_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "게시글 관리"}
                                    """))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(ErrorCode.FEATURE_NOT_ACCESSIBLE.getCode()));
        }
    }

    @Nested
    @DisplayName("구조 변경 — 승인·병합·분리·삭제")
    class StructuralActions {

        @Test
        @DisplayName("승인은 본문 없이 200이다")
        void confirmsFeature() throws Exception {
            mockMvc.perform(post(FEATURE_PATH + "/confirm", PROJECT_ID, SPEC_DOCUMENT_ID, FEATURE_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isOk());

            verify(featureReviewService).confirm(PROJECT_ID, SPEC_DOCUMENT_ID, USER_ID, FEATURE_ID);
        }

        @Test
        @DisplayName("일괄 승인은 본문 없이 200이다")
        void confirmsAll() throws Exception {
            mockMvc.perform(post(CONFIRM_ALL_PATH, PROJECT_ID, SPEC_DOCUMENT_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isOk());

            verify(featureReviewService).confirmAll(PROJECT_ID, SPEC_DOCUMENT_ID, USER_ID);
        }

        @Test
        @DisplayName("병합에 합칠 상대가 없으면 400이고 서비스를 호출하지 않는다")
        void rejectsMergeWithoutTarget() throws Exception {
            mockMvc.perform(post(FEATURE_PATH + "/merge", PROJECT_ID, SPEC_DOCUMENT_ID, FEATURE_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "게시글 관리"}
                                    """))
                    .andExpect(status().isBadRequest());

            verify(featureReviewService, never()).merge(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("중복으로 지목되지 않은 기능을 합치려 하면 400 FEATURE-004다")
        void returnsMergeNotAllowed() throws Exception {
            willThrow(new BadRequestException(ErrorCode.FEATURE_MERGE_NOT_ALLOWED))
                    .given(featureReviewService).merge(eq(PROJECT_ID), eq(SPEC_DOCUMENT_ID),
                            eq(USER_ID), eq(FEATURE_ID), any());

            mockMvc.perform(post(FEATURE_PATH + "/merge", PROJECT_ID, SPEC_DOCUMENT_ID, FEATURE_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"targetFeatureId": 19, "name": "게시글 관리"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.FEATURE_MERGE_NOT_ALLOWED.getCode()));
        }

        @Test
        @DisplayName("분리 추천안이 비어 있으면 400이고 서비스를 호출하지 않는다")
        void rejectsEmptySplit() throws Exception {
            mockMvc.perform(post(FEATURE_PATH + "/split", PROJECT_ID, SPEC_DOCUMENT_ID, FEATURE_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"features": []}
                                    """))
                    .andExpect(status().isBadRequest());

            verify(featureReviewService, never()).split(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("삭제는 본문 없이 200이다")
        void deletesFeature() throws Exception {
            mockMvc.perform(delete(FEATURE_PATH, PROJECT_ID, SPEC_DOCUMENT_ID, FEATURE_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isOk());

            verify(featureReviewService).delete(PROJECT_ID, SPEC_DOCUMENT_ID, USER_ID, FEATURE_ID);
        }

        @Test
        @DisplayName("같은 요청이 겹치면 409 FEATURE-006이다")
        void returnsConflict() throws Exception {
            willThrow(new ConflictException(ErrorCode.FEATURE_REVIEW_CONFLICT))
                    .given(featureReviewService).delete(PROJECT_ID, SPEC_DOCUMENT_ID, USER_ID, FEATURE_ID);

            mockMvc.perform(delete(FEATURE_PATH, PROJECT_ID, SPEC_DOCUMENT_ID, FEATURE_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(ErrorCode.FEATURE_REVIEW_CONFLICT.getCode()));
        }
    }
}
