package com.github.galpiii.galpi.domain.featurematch.controller;

import com.github.galpiii.galpi.domain.auth.jwt.JwtTokenProvider;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchFilter;
import com.github.galpiii.galpi.domain.featurematch.dto.request.FeaturePrMatchesCreateRequest;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchRunCreatedResponse;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchRunStatusResponse;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import org.junit.jupiter.params.provider.EnumSource;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeaturePrMatchesCreatedResponse;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunStatus;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.domain.github.exception.GithubReauthRequiredException;
import com.github.galpiii.galpi.support.WebMvcTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FeatureMatchControllerTest extends WebMvcTestSupport {

    @Test
    @DisplayName("최신 실행 조회는 인증이 필요하다")
    void latestRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/projects/3/feature-match-runs/latest"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(featureMatchRunService);
    }

    @Test
    @DisplayName("최신 실행 ID와 복구에 필요한 상태를 반환한다")
    void returnsLatestRun() throws Exception {
        var now = OffsetDateTime.parse("2026-10-05T06:00:00Z");
        given(featureMatchRunService.latest(3L, 7L)).willReturn(
                new FeatureMatchRunStatusResponse(
                        123, FeatureMatchRunStatus.RUNNING, 10, 2, 20, 10, 1, 9, 0, 0,
                        45, null, now, null, now));
        mockMvc.perform(get("/projects/3/feature-match-runs/latest").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.featureMatchRunId").value(123))
                .andExpect(jsonPath("$.data.specDocumentId").value(10))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.progressPercent").value(45))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.startedAt").exists());
        verify(featureMatchRunService).latest(3L, 7L);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class,
            names = {"FEATURE_MATCH_RUN_NOT_FOUND", "PROJECT_NOT_FOUND"})
    @DisplayName("최신 실행의 실행 없음 및 접근 불가 오류는 404다")
    void latestNotFound(ErrorCode code) throws Exception {
        given(featureMatchRunService.latest(3L, 7L))
                .willThrow(new NotFoundException(code));
        mockMvc.perform(get("/projects/3/feature-match-runs/latest").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(code.getCode()));
    }


    @Autowired
    private JwtTokenProvider tokenProvider;

    private String bearer() {
        return "Bearer " + tokenProvider.createAccessToken(7L);
    }

    @Test
    @DisplayName("인증 없는 접근을 거부한다")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/projects/3/feature-match-runs"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(featureMatchRunService);
    }

    @Test
    @DisplayName("실행 접수는 202 응답을 반환한다")
    void createsRunWithAcceptedResponse() throws Exception {
        given(featureMatchRunService.create(3L, 7L)).willReturn(
                new FeatureMatchRunCreatedResponse(10L, FeatureMatchRunStatus.QUEUED,
                        4L, 2, 1, 3, 0, 0, OffsetDateTime.now()));
        mockMvc.perform(post("/projects/3/feature-match-runs").header("Authorization", bearer()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.featureMatchRunId").value(10))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @Test
    @DisplayName("PR 분석 진행 중에는 실행을 거부한다")
    void rejectsRunWhilePrAnalysisIsPending() throws Exception {
        given(featureMatchRunService.create(3L, 7L))
                .willThrow(new ConflictException(ErrorCode.FEATURE_MATCH_PR_NOT_READY));
        mockMvc.perform(post("/projects/3/feature-match-runs").header("Authorization", bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEATURE-MATCH-002"));
    }

    @Test
    @DisplayName("GitHub 재연결 필요는 기존 공통 응답인 401을 유지한다")
    void githubReauthPreservesExistingResponse() throws Exception {
        given(featureMatchRunService.create(3L, 7L))
                .willThrow(new GithubReauthRequiredException());
        mockMvc.perform(post("/projects/3/feature-match-runs").header("Authorization", bearer()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GITHUB-001"));
    }

    @Test
    @DisplayName("저장소·검색·근거 필터를 서비스에 전달한다")
    void passesResultFilters() throws Exception {
        mockMvc.perform(get("/projects/3/feature-match-results").header("Authorization", bearer())
                        .param("repositoryId", "8").param("q", "가입").param("filter", "ATTENTION_REQUIRED"))
                .andExpect(status().isOk());
        verify(featureMatchQueryService).results(3L, 7L, 8L, "가입", FeatureMatchFilter.ATTENTION_REQUIRED);
    }

    @Test
    @DisplayName("정의되지 않은 필터를 거부한다")
    void rejectsUnknownFilter() throws Exception {
        mockMvc.perform(get("/projects/3/feature-match-results").header("Authorization", bearer())
                        .param("filter", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(featureMatchQueryService);
    }

    @Test
    @DisplayName("여러 PR ID를 배열로 받는다")
    void bindsMultiplePrIds() throws Exception {
        var request = new FeaturePrMatchesCreateRequest(List.of(11L, 12L));
        given(featurePrMatchService.create(5L, 7L, request))
                .willReturn(new FeaturePrMatchesCreatedResponse(List.of()));
        mockMvc.perform(post("/features/5/pull-request-matches").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pullRequestIds\":[11,12]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.createdMatches").isArray());
        verify(featurePrMatchService).create(5L, 7L, request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"pullRequestIds\":[]}", "{\"pullRequestIds\":[null]}",
            "{\"pullRequestIds\":[0]}", "{\"pullRequestIds\":[-1]}"})
    @DisplayName("잘못된 PR ID 배열을 거부한다")
    void rejectsInvalidPrIds(String body) throws Exception {
        mockMvc.perform(post("/features/5/pull-request-matches").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(featurePrMatchService);
    }

    @Test
    @DisplayName("연결 삭제는 빈 성공 응답을 반환한다")
    void deletesOnlyMatchAndReturnsEmptyEnvelope() throws Exception {
        mockMvc.perform(delete("/feature-pr-matches/9").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));
        verify(featurePrMatchService).delete(9L, 7L);
    }

    @Test
    @DisplayName("상세 및 상태 조회 식별자를 전달한다")
    void passesDetailAndStatusIds() throws Exception {
        mockMvc.perform(get("/features/5/feature-match-result").header("Authorization", bearer())
                        .param("repositoryId", "8"))
                .andExpect(status().isOk());
        verify(featureMatchQueryService).detail(5L, 7L, 8L);
        mockMvc.perform(get("/feature-match-runs/10").header("Authorization", bearer()))
                .andExpect(status().isOk());
        verify(featureMatchRunService).status(10L, 7L);
    }

    @Test
    @DisplayName("미매칭 조회에 기본 페이지 값을 적용한다")
    void passesDefaultPagination() throws Exception {
        mockMvc.perform(get("/projects/3/feature-match-results/unmatched-pull-requests")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk());
        verify(featureMatchQueryService).unmatched(3L, 7L, null, null, 0, 20);
    }
}
