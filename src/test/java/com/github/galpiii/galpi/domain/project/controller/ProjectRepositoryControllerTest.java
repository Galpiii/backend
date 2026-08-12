package com.github.galpiii.galpi.domain.project.controller;

import com.github.galpiii.galpi.domain.auth.jwt.JwtTokenProvider;
import com.github.galpiii.galpi.domain.project.dto.LinkedRepositoryResponse;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ForbiddenException;
import com.github.galpiii.galpi.support.WebMvcTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("프로젝트 저장소 컨트롤러 — 연결·해제")
class ProjectRepositoryControllerTest extends WebMvcTestSupport {

    private static final long USER_ID = 7L;
    private static final long PROJECT_ID = 3L;

    @Autowired
    private JwtTokenProvider tokenProvider;

    private String bearer() {
        return "Bearer " + tokenProvider.createAccessToken(USER_ID);
    }

    private static LinkedRepositoryResponse linked(long repositoryId, String fullName) {
        return new LinkedRepositoryResponse(repositoryId, 1L, 100L, "wb", "notes", fullName,
                true, "main", "https://github.com/" + fullName, "ACCESSIBLE", OffsetDateTime.now());
    }

    @Nested
    @DisplayName("연결 — POST /projects/{projectId}/repositories")
    class Link {

        @Test
        @DisplayName("선택한 저장소 id를 서비스로 넘긴다")
        void passesRequestedIds() throws Exception {
            given(projectRepositoryService.link(anyLong(), anyLong(), any()))
                    .willReturn(List.of(linked(55L, "wb/notes")));

            mockMvc.perform(post("/projects/{projectId}/repositories", PROJECT_ID)
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"githubRepositoryIds\":[1,2]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].repositoryId").value(55));

            verify(projectRepositoryService).link(USER_ID, PROJECT_ID, List.of(1L, 2L));
        }

        @Test
        @DisplayName("접근 권한 없는 저장소는 403으로 떨어진다")
        void rejectsInaccessibleRepository() throws Exception {
            willThrow(new ForbiddenException(ErrorCode.GITHUB_REPOSITORY_ACCESS_DENIED))
                    .given(projectRepositoryService).link(anyLong(), anyLong(), any());

            mockMvc.perform(post("/projects/{projectId}/repositories", PROJECT_ID)
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"githubRepositoryIds\":[999]}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code")
                            .value(ErrorCode.GITHUB_REPOSITORY_ACCESS_DENIED.getCode()));
        }

        @Test
        @DisplayName("빈 목록은 400이다")
        void rejectsEmptySelection() throws Exception {
            mockMvc.perform(post("/projects/{projectId}/repositories", PROJECT_ID)
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"githubRepositoryIds\":[]}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("토큰 없이 부르면 401이다")
        void requiresAuthentication() throws Exception {
            mockMvc.perform(post("/projects/{projectId}/repositories", PROJECT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"githubRepositoryIds\":[1]}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("조회·해제")
    class ListAndUnlink {

        @Test
        @DisplayName("연결된 저장소 목록을 돌려준다")
        void listsLinkedRepositories() throws Exception {
            given(projectRepositoryService.list(USER_ID, PROJECT_ID))
                    .willReturn(List.of(linked(55L, "wb/notes")));

            mockMvc.perform(get("/projects/{projectId}/repositories", PROJECT_ID)
                            .header("Authorization", bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].fullName").value("wb/notes"))
                    .andExpect(jsonPath("$.data[0].accessStatus").value("ACCESSIBLE"));
        }

        @Test
        @DisplayName("연결을 끊는다")
        void unlinksRepository() throws Exception {
            mockMvc.perform(delete("/projects/{projectId}/repositories/{repositoryId}",
                            PROJECT_ID, 55L)
                            .header("Authorization", bearer()))
                    .andExpect(status().isOk());

            verify(projectRepositoryService).unlink(USER_ID, PROJECT_ID, 55L);
        }
    }
}
