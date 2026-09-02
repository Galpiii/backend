package com.github.galpiii.galpi.domain.pullrequest.controller;

import com.github.galpiii.galpi.domain.auth.jwt.JwtTokenProvider;
import com.github.galpiii.galpi.domain.collection.entity.ChangeStatus;
import com.github.galpiii.galpi.domain.collection.entity.DataCompleteness;
import com.github.galpiii.galpi.domain.consent.exception.AiDataConsentRequiredException;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestAnalysisResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestAnalysisRetryResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestAuthorResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestDetailResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestListItemResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestListResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestOverviewResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestRepositoryResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestSort;
import com.github.galpiii.galpi.domain.pullrequest.entity.ChangeType;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.domain.pullrequest.entity.SummaryFailureCode;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import com.github.galpiii.galpi.support.WebMvcTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("PR 컨트롤러 — 목록·집계·상세·재요약")
class PullRequestControllerTest extends WebMvcTestSupport {

    private static final long USER_ID = 7L;
    private static final long PROJECT_ID = 3L;
    private static final long PULL_REQUEST_ID = 1024L;

    @Autowired
    private JwtTokenProvider tokenProvider;

    private String bearer() {
        return "Bearer " + tokenProvider.createAccessToken(USER_ID);
    }

    @Nested
    @DisplayName("목록 — GET /projects/{projectId}/pull-requests")
    class ListPullRequests {

        @Test
        @DisplayName("필터 없이 부르면 기본값으로 조회한다")
        void listsWithDefaults() throws Exception {
            given(pullRequestQueryService.list(anyLong(), anyLong(), any(), any(), any(), any(),
                    any(), any(), any())).willReturn(listResponse());

            mockMvc.perform(get("/projects/{projectId}/pull-requests", PROJECT_ID)
                            .header("Authorization", bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pullRequests[0].id").value(PULL_REQUEST_ID))
                    .andExpect(jsonPath("$.data.pullRequests[0].state").value("MERGED"))
                    .andExpect(jsonPath("$.data.pullRequests[0].excluded").value(false))
                    .andExpect(jsonPath("$.data.pullRequests[0].analysis.changeType")
                            .value("FEATURE"))
                    .andExpect(jsonPath("$.data.totalElements").value(1));

            verify(pullRequestQueryService).list(USER_ID, PROJECT_ID, null, null, null, null,
                    null, null, null);
        }

        @Test
        @DisplayName("목록 응답에는 요약 본문이 없다")
        void omitsSummaryBody() throws Exception {
            given(pullRequestQueryService.list(anyLong(), anyLong(), any(), any(), any(), any(),
                    any(), any(), any())).willReturn(listResponse());

            mockMvc.perform(get("/projects/{projectId}/pull-requests", PROJECT_ID)
                            .header("Authorization", bearer()))
                    .andExpect(jsonPath("$.data.pullRequests[0].analysis.summary").doesNotExist());
        }

        @Test
        @DisplayName("쿼리 파라미터를 그대로 넘긴다")
        void passesQueryParameters() throws Exception {
            given(pullRequestQueryService.list(anyLong(), anyLong(), any(), any(), any(), any(),
                    any(), any(), any())).willReturn(listResponse());

            mockMvc.perform(get("/projects/{projectId}/pull-requests", PROJECT_ID)
                            .header("Authorization", bearer())
                            .param("repositoryId", "7")
                            .param("authorLogin", "developerA")
                            .param("q", "회원")
                            .param("analysisStatus", "COMPLETED")
                            .param("sort", "NUMBER_ASC")
                            .param("page", "1")
                            .param("size", "50"))
                    .andExpect(status().isOk());

            verify(pullRequestQueryService).list(USER_ID, PROJECT_ID, 7L, "developerA", "회원",
                    PullRequestAnalysisStatus.COMPLETED, PullRequestSort.NUMBER_ASC, 1, 50);
        }

        @Test
        @DisplayName("남의 프로젝트는 404다")
        void returnsNotFound() throws Exception {
            willThrow(new NotFoundException(ErrorCode.PROJECT_NOT_FOUND))
                    .given(pullRequestQueryService).list(anyLong(), anyLong(), any(), any(), any(),
                            any(), any(), any(), any());

            mockMvc.perform(get("/projects/{projectId}/pull-requests", PROJECT_ID)
                            .header("Authorization", bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PROJECT-001"));
        }

        @Test
        @DisplayName("인증 없이는 401이다")
        void requiresAuthentication() throws Exception {
            mockMvc.perform(get("/projects/{projectId}/pull-requests", PROJECT_ID))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("헤더 집계 — GET /projects/{projectId}/pull-requests/summary")
    class Overview {

        @Test
        @DisplayName("집계와 고정된 수집 기준을 함께 준다")
        void returnsOverview() throws Exception {
            given(pullRequestQueryService.overview(anyLong(), anyLong()))
                    .willReturn(PullRequestOverviewResponse.from(List.of()));

            mockMvc.perform(get("/projects/{projectId}/pull-requests/summary", PROJECT_ID)
                            .header("Authorization", bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalCount").value(0))
                    .andExpect(jsonPath("$.data.excludedCount").value(0))
                    .andExpect(jsonPath("$.data.criteria.state").value("MERGED"))
                    .andExpect(jsonPath("$.data.criteria.baseBranch").value("DEFAULT"))
                    .andExpect(jsonPath("$.data.criteria.period").value("ALL"));

            verify(pullRequestQueryService).overview(USER_ID, PROJECT_ID);
        }
    }

    @Nested
    @DisplayName("상세 — GET /pull-requests/{pullRequestId}")
    class Detail {

        @Test
        @DisplayName("GitHub 원본과 AI 분석을 함께 주고 diff 원문은 담지 않는다")
        void returnsDetail() throws Exception {
            given(pullRequestQueryService.detail(anyLong(), anyLong())).willReturn(detail());

            mockMvc.perform(get("/pull-requests/{pullRequestId}", PULL_REQUEST_ID)
                            .header("Authorization", bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.number").value(42))
                    .andExpect(jsonPath("$.data.state").value("MERGED"))
                    .andExpect(jsonPath("$.data.analysis.summary").value("회원가입을 추가했습니다."))
                    .andExpect(jsonPath("$.data.commitsTruncated").value(false))
                    .andExpect(jsonPath("$.data.files[0].patch").doesNotExist())
                    // 기능대조가 없으므로 빈 배열조차 내리지 않는다.
                    .andExpect(jsonPath("$.data.relatedFeatures").doesNotExist());

            verify(pullRequestQueryService).detail(USER_ID, PULL_REQUEST_ID);
        }

        @Test
        @DisplayName("요약이 실패했으면 summary 없이 errorCode를 준다")
        void returnsFailedAnalysis() throws Exception {
            given(pullRequestQueryService.detail(anyLong(), anyLong()))
                    .willReturn(withAnalysis(new PullRequestAnalysisResponse(
                            PullRequestAnalysisStatus.FAILED, null, null, null,
                            SummaryFailureCode.SUMMARY_LLM_FAILED)));

            mockMvc.perform(get("/pull-requests/{pullRequestId}", PULL_REQUEST_ID)
                            .header("Authorization", bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.analysis.status").value("FAILED"))
                    .andExpect(jsonPath("$.data.analysis.summary").doesNotExist())
                    .andExpect(jsonPath("$.data.analysis.errorCode").value("SUMMARY_LLM_FAILED"));
        }

        @Test
        @DisplayName("아직 인계되지 않은 PR은 analysis가 비어 온다")
        void returnsMissingAnalysis() throws Exception {
            given(pullRequestQueryService.detail(anyLong(), anyLong()))
                    .willReturn(withAnalysis(null));

            mockMvc.perform(get("/pull-requests/{pullRequestId}", PULL_REQUEST_ID)
                            .header("Authorization", bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.analysis").doesNotExist());
        }

        @Test
        @DisplayName("남의 PR은 PULL-REQUEST-001로 404다")
        void returnsNotFound() throws Exception {
            willThrow(new NotFoundException(ErrorCode.PULL_REQUEST_NOT_FOUND))
                    .given(pullRequestQueryService).detail(anyLong(), anyLong());

            mockMvc.perform(get("/pull-requests/{pullRequestId}", PULL_REQUEST_ID)
                            .header("Authorization", bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PULL-REQUEST-001"));
        }
    }

    @Nested
    @DisplayName("재요약 — POST /projects/{projectId}/pull-request-analyses/retry")
    class Retry {

        @Test
        @DisplayName("202와 되돌린 개수를 준다")
        void requeues() throws Exception {
            given(pullRequestAnalysisRetryService.retry(anyLong(), anyLong(), any()))
                    .willReturn(new PullRequestAnalysisRetryResponse(3));

            mockMvc.perform(post("/projects/{projectId}/pull-request-analyses/retry", PROJECT_ID)
                            .header("Authorization", bearer()))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.requeuedCount").value(3));

            verify(pullRequestAnalysisRetryService).retry(eq(USER_ID), eq(PROJECT_ID), isNull());
        }

        @Test
        @DisplayName("되돌릴 것이 없어도 202다")
        void acceptsZero() throws Exception {
            given(pullRequestAnalysisRetryService.retry(anyLong(), anyLong(), any()))
                    .willReturn(new PullRequestAnalysisRetryResponse(0));

            mockMvc.perform(post("/projects/{projectId}/pull-request-analyses/retry", PROJECT_ID)
                            .header("Authorization", bearer()))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.requeuedCount").value(0));
        }

        @Test
        @DisplayName("저장소를 좁힐 수 있다")
        void narrowsToRepository() throws Exception {
            given(pullRequestAnalysisRetryService.retry(anyLong(), anyLong(), any()))
                    .willReturn(new PullRequestAnalysisRetryResponse(1));

            mockMvc.perform(post("/projects/{projectId}/pull-request-analyses/retry", PROJECT_ID)
                            .header("Authorization", bearer())
                            .param("repositoryId", "7"))
                    .andExpect(status().isAccepted());

            verify(pullRequestAnalysisRetryService).retry(USER_ID, PROJECT_ID, 7L);
        }

        @Test
        @DisplayName("동의가 없으면 CONSENT-001이다")
        void requiresConsent() throws Exception {
            willThrow(new AiDataConsentRequiredException())
                    .given(pullRequestAnalysisRetryService).retry(anyLong(), anyLong(), any());

            mockMvc.perform(post("/projects/{projectId}/pull-request-analyses/retry", PROJECT_ID)
                            .header("Authorization", bearer()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CONSENT-001"));
        }
    }

    private static PullRequestListResponse listResponse() {
        return new PullRequestListResponse(List.of(new PullRequestListItemResponse(
                PULL_REQUEST_ID, 42, "feat: 회원가입 API 구현", "MERGED", OffsetDateTime.now(),
                "https://github.com/sample-org/backend/pull/42",
                new PullRequestAuthorResponse("developerA", "https://avatar"),
                new PullRequestRepositoryResponse(7L, "sample-org/backend"),
                PullRequestAnalysisResponse.withoutSummary(PullRequestAnalysisStatus.COMPLETED,
                        ChangeType.FEATURE, OffsetDateTime.now(), null),
                false, DataCompleteness.COMPLETE)), 0, 20, 1, 1);
    }

    private static PullRequestDetailResponse detail() {
        return new PullRequestDetailResponse(
                PULL_REQUEST_ID, 42, "feat: 회원가입 API 구현", "본문", "MERGED", "develop",
                "feature/signup", OffsetDateTime.now(), OffsetDateTime.now(),
                "https://github.com/sample-org/backend/pull/42",
                new PullRequestAuthorResponse("developerA", "https://avatar"),
                new PullRequestRepositoryResponse(7L, "sample-org/backend"),
                new PullRequestDetailResponse.Stats(2, 180, 12),
                List.of(new PullRequestDetailResponse.FileResponse(
                        "src/AuthController.java", null, ChangeStatus.ADDED,
                        120, 0, 120, false)),
                false,
                List.of(new PullRequestDetailResponse.CommitResponse(
                        "a3f9c21", "feat: 회원가입 API 구현", "developerA", OffsetDateTime.now())),
                false,
                new PullRequestAnalysisResponse(PullRequestAnalysisStatus.COMPLETED,
                        "회원가입을 추가했습니다.", ChangeType.FEATURE, OffsetDateTime.now(), null),
                DataCompleteness.COMPLETE, List.of());
    }

    /** 분석 부분만 바꾼 상세. 화면이 "분석 실패" 배지와 "분석 대기"를 구분해 그린다. */
    private static PullRequestDetailResponse withAnalysis(PullRequestAnalysisResponse analysis) {
        PullRequestDetailResponse base = detail();
        return new PullRequestDetailResponse(base.id(), base.number(), base.title(), base.body(),
                base.state(), base.baseRef(), base.headRef(), base.mergedAt(),
                base.createdAtGithub(), base.htmlUrl(), base.author(), base.repository(),
                base.stats(), base.files(), base.filesTruncated(), base.commits(),
                base.commitsTruncated(), analysis,
                base.dataCompleteness(), base.incompleteReasons());
    }
}
