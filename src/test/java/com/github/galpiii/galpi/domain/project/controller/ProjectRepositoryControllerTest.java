package com.github.galpiii.galpi.domain.project.controller;

import com.github.galpiii.galpi.domain.auth.jwt.JwtTokenProvider;
import com.github.galpiii.galpi.domain.github.dto.SelectableRepositoryResponse;
import com.github.galpiii.galpi.domain.project.dto.LinkRepositoriesRequest;
import com.github.galpiii.galpi.domain.project.dto.LinkedRepositoryResponse;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ForbiddenException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import com.github.galpiii.galpi.support.WebMvcTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
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
        @DisplayName("상한을 넘는 목록은 400이다")
        void rejectsOversizedSelection() throws Exception {
            String ids = java.util.stream.LongStream
                    .rangeClosed(1, LinkRepositoriesRequest.MAX_REPOSITORIES + 1)
                    .mapToObj(Long::toString)
                    .collect(java.util.stream.Collectors.joining(","));

            mockMvc.perform(post("/projects/{projectId}/repositories", PROJECT_ID)
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"githubRepositoryIds\":[" + ids + "]}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("음수 저장소 id는 400이다")
        void rejectsNonPositiveId() throws Exception {
            mockMvc.perform(post("/projects/{projectId}/repositories", PROJECT_ID)
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"githubRepositoryIds\":[-1]}"))
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

    @Nested
    @DisplayName("URL로 찾기 — POST /projects/{projectId}/repositories/resolve")
    class Resolve {

        @Test
        @DisplayName("확인된 저장소 정보를 돌려준다")
        void resolvesRepository() throws Exception {
            given(projectRepositoryService.resolve(anyLong(), anyLong(), any()))
                    .willReturn(new SelectableRepositoryResponse(1L, "galpiii", "backend",
                            "galpiii/backend", true, "main", "https://github.com/galpiii/backend",
                            "동아리 통합 플랫폼 백엔드 API", "Java",
                            OffsetDateTime.parse("2026-08-18T00:00:00Z"),
                            Map.of("pull", true), false));

            mockMvc.perform(post("/projects/{projectId}/repositories/resolve", PROJECT_ID)
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://github.com/galpiii/backend.git\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.githubRepositoryId").value(1))
                    .andExpect(jsonPath("$.data.language").value("Java"))
                    .andExpect(jsonPath("$.data.linked").value(false));

            verify(projectRepositoryService).resolve(USER_ID, PROJECT_ID,
                    "https://github.com/galpiii/backend.git");
        }

        @Test
        @DisplayName("접근할 수 없는 저장소는 없는 것과 같은 404로 나간다")
        void hidesInaccessibleRepository() throws Exception {
            willThrow(new NotFoundException(ErrorCode.PROJECT_REPOSITORY_NOT_ACCESSIBLE))
                    .given(projectRepositoryService).resolve(anyLong(), anyLong(), any());

            mockMvc.perform(post("/projects/{projectId}/repositories/resolve", PROJECT_ID)
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://github.com/someone/private\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code")
                            .value(ErrorCode.PROJECT_REPOSITORY_NOT_ACCESSIBLE.getCode()));
        }

        @Test
        @DisplayName("빈 URL은 서비스까지 가지 않는다")
        void rejectsBlankUrl() throws Exception {
            mockMvc.perform(post("/projects/{projectId}/repositories/resolve", PROJECT_ID)
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"  \"}"))
                    .andExpect(status().isBadRequest());

            verify(projectRepositoryService, never()).resolve(anyLong(), anyLong(), any());
        }
    }
}
