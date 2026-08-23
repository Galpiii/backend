package com.github.galpiii.galpi.domain.project.controller;

import com.github.galpiii.galpi.domain.auth.jwt.JwtTokenProvider;
import com.github.galpiii.galpi.domain.project.dto.ProjectCreateRequest;
import com.github.galpiii.galpi.domain.project.dto.ProjectCreatedResponse;
import com.github.galpiii.galpi.domain.project.dto.ProjectDetailResponse;
import com.github.galpiii.galpi.domain.project.dto.ProjectListResponse;
import com.github.galpiii.galpi.domain.project.dto.ProjectSort;
import com.github.galpiii.galpi.domain.project.dto.ProjectSummaryResponse;
import com.github.galpiii.galpi.domain.project.dto.ProjectUpdateRequest;
import com.github.galpiii.galpi.domain.project.entity.ProjectOnboardingStep;
import com.github.galpiii.galpi.domain.project.entity.ProjectStatus;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("프로젝트 컨트롤러 — CRUD")
class ProjectControllerTest extends WebMvcTestSupport {

    private static final long USER_ID = 7L;
    private static final long PROJECT_ID = 3L;

    @Autowired
    private JwtTokenProvider tokenProvider;

    private String bearer() {
        return "Bearer " + tokenProvider.createAccessToken(USER_ID);
    }

    private static ProjectCreatedResponse created(String name) {
        return new ProjectCreatedResponse(PROJECT_ID, name, ProjectStatus.DRAFT,
                ProjectOnboardingStep.SPEC);
    }

    private static ProjectDetailResponse detail() {
        return new ProjectDetailResponse(PROJECT_ID, "갈피", "DRAFT", "SPEC", List.of(), null,
                null, OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Nested
    @DisplayName("생성 — POST /projects")
    class Create {

        @Test
        @DisplayName("이름만 보내면 DRAFT 프로젝트가 201로 만들어진다")
        void createsProject() throws Exception {
            given(projectService.create(anyLong(), any())).willReturn(created("갈피"));

            mockMvc.perform(post("/projects")
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"갈피\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(PROJECT_ID))
                    .andExpect(jsonPath("$.data.status").value("DRAFT"))
                    .andExpect(jsonPath("$.data.onboardingStep").value("SPEC"));

            verify(projectService).create(USER_ID, new ProjectCreateRequest("갈피"));
        }

        @Test
        @DisplayName("본문에 ownerId를 넣어도 무시하고 인증된 사용자로 만든다")
        void ignoresOwnerIdInBody() throws Exception {
            given(projectService.create(anyLong(), any())).willReturn(created("갈피"));

            mockMvc.perform(post("/projects")
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"갈피\",\"ownerId\":9999}"))
                    .andExpect(status().isCreated());

            verify(projectService).create(USER_ID, new ProjectCreateRequest("갈피"));
        }

        @Test
        @DisplayName("빈 이름과 공백뿐인 이름은 거부한다")
        void rejectsBlankName() throws Exception {
            mockMvc.perform(post("/projects")
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

            mockMvc.perform(post("/projects")
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"   \"}"))
                    .andExpect(status().isBadRequest());

            verify(projectService, never()).create(anyLong(), any());
        }

        @Test
        @DisplayName("100자를 넘는 이름은 거부한다")
        void rejectsTooLongName() throws Exception {
            String name = "가".repeat(101);

            mockMvc.perform(post("/projects")
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + name + "\"}"))
                    .andExpect(status().isBadRequest());

            verify(projectService, never()).create(anyLong(), any());
        }

        @Test
        @DisplayName("토큰 없이 부르면 401이다")
        void requiresAuthentication() throws Exception {
            mockMvc.perform(post("/projects")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"갈피\"}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("목록 — GET /projects")
    class ListProjects {

        @Test
        @DisplayName("빈 목록도 빈 배열로 내려간다")
        void returnsEmptyArray() throws Exception {
            given(projectService.list(anyLong(), any(), any(), any(), any()))
                    .willReturn(new ProjectListResponse(List.of(), 0, 20, 0, 0));

            mockMvc.perform(get("/projects").header("Authorization", bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.projects").isArray())
                    .andExpect(jsonPath("$.data.projects").isEmpty());

            verify(projectService).list(USER_ID, null, null, null, null);
        }

        @Test
        @DisplayName("배지에 필요한 값을 항목마다 함께 준다")
        void carriesBadgeFields() throws Exception {
            given(projectService.list(anyLong(), any(), any(), any(), any()))
                    .willReturn(new ProjectListResponse(List.of(new ProjectSummaryResponse(
                            PROJECT_ID, "갈피", ProjectStatus.DRAFT, ProjectOnboardingStep.SPEC,
                            0, false, null, OffsetDateTime.now())), 0, 20, 1, 1));

            mockMvc.perform(get("/projects")
                            .param("status", "DRAFT")
                            .param("sort", "NAME")
                            .header("Authorization", bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.projects[0].repositoryCount").value(0))
                    .andExpect(jsonPath("$.data.projects[0].hasSpecDocument").value(false))
                    .andExpect(jsonPath("$.data.projects[0].onboardingStep").value("SPEC"));

            verify(projectService)
                    .list(USER_ID, ProjectStatus.DRAFT, ProjectSort.NAME, null, null);
        }
    }

    @Nested
    @DisplayName("상세·수정·삭제")
    class Detail {

        @Test
        @DisplayName("남의 프로젝트를 조회하면 404다")
        void hidesForeignProject() throws Exception {
            willThrow(new NotFoundException(ErrorCode.PROJECT_NOT_FOUND))
                    .given(projectService).get(anyLong(), anyLong());

            mockMvc.perform(get("/projects/{projectId}", PROJECT_ID)
                            .header("Authorization", bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(ErrorCode.PROJECT_NOT_FOUND.getCode()));
        }

        @Test
        @DisplayName("이름과 상태를 함께 고칠 수 있다")
        void updatesProject() throws Exception {
            given(projectService.update(anyLong(), anyLong(), any())).willReturn(detail());

            mockMvc.perform(patch("/projects/{projectId}", PROJECT_ID)
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"갈피 v2\",\"status\":\"ARCHIVED\"}"))
                    .andExpect(status().isOk());

            verify(projectService).update(USER_ID, PROJECT_ID,
                    new ProjectUpdateRequest("갈피 v2", ProjectStatus.ARCHIVED, null));
        }

        @Test
        @DisplayName("허용되지 않은 상태 전이는 400으로 떨어진다")
        void rejectsForbiddenTransition() throws Exception {
            willThrow(new BadRequestException(ErrorCode.PROJECT_STATUS_TRANSITION_NOT_ALLOWED))
                    .given(projectService).update(anyLong(), anyLong(), any());

            mockMvc.perform(patch("/projects/{projectId}", PROJECT_ID)
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"DRAFT\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value(ErrorCode.PROJECT_STATUS_TRANSITION_NOT_ALLOWED.getCode()));
        }

        @Test
        @DisplayName("삭제는 서비스로 넘어간다")
        void deletesProject() throws Exception {
            mockMvc.perform(delete("/projects/{projectId}", PROJECT_ID)
                            .header("Authorization", bearer()))
                    .andExpect(status().isOk());

            verify(projectService).delete(USER_ID, PROJECT_ID);
        }
    }
}
