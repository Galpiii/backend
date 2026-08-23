package com.github.galpiii.galpi.domain.project.controller;

import com.github.galpiii.galpi.domain.auth.jwt.AuthPrincipal;
import com.github.galpiii.galpi.domain.project.dto.ProjectCreateRequest;
import com.github.galpiii.galpi.domain.project.dto.ProjectCreatedResponse;
import com.github.galpiii.galpi.domain.project.dto.ProjectDetailResponse;
import com.github.galpiii.galpi.domain.project.dto.ProjectListResponse;
import com.github.galpiii.galpi.domain.project.dto.ProjectSort;
import com.github.galpiii.galpi.domain.project.dto.ProjectUpdateRequest;
import com.github.galpiii.galpi.domain.project.entity.ProjectStatus;
import com.github.galpiii.galpi.domain.project.service.ProjectService;
import com.github.galpiii.galpi.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "프로젝트")
@RestController
@RequiredArgsConstructor
@RequestMapping("/projects")
public class ProjectController {

    private final ProjectService projectService;

    @Operation(summary = "프로젝트 목록",
            description = """
                    본인 소유의 프로젝트만 돌려준다. status를 주지 않으면 ARCHIVED를 제외하고,
                    삭제된 프로젝트는 어떤 경우에도 나오지 않는다. 결과가 0건이면 빈 배열이다 —
                    빈 상태 화면을 띄울지는 프론트가 정한다.""")
    @GetMapping
    public ResponseEntity<ApiResponse<ProjectListResponse>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(name = "status", required = false) ProjectStatus status,
            @RequestParam(name = "sort", required = false) ProjectSort sort,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        return ResponseEntity.ok(ApiResponse.success(
                projectService.list(principal.userId(), status, sort, page, size)));
    }

    @Operation(summary = "프로젝트 생성",
            description = """
                    위저드 ① 단계에서 부른다. 이름만 있으면 만들어지고, 상태는 DRAFT,
                    온보딩 단계는 SPEC으로 시작한다. 소유자는 인증된 사용자로 서버가 채우므로
                    본문에 ownerId를 넣어도 무시된다. 명세서 PDF는 이 요청에 싣지 않고
                    POST /projects/{projectId}/feature-specs로 따로 올린다 — 업로드가 실패해도
                    프로젝트는 남아야 하기 때문이다.

                    GitHub 연결 상태와 무관하게 만들 수 있다.

                    사용자당 개수 상한(PROJECT_MAX_PER_USER)에 걸리면 PROJECT-004다. 이 값은
                    남용 방지용 soft limit이라 동시 요청이 겹치면 근소하게 초과될 수 있다.""")
    @PostMapping
    public ResponseEntity<ApiResponse<ProjectCreatedResponse>> create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ProjectCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(projectService.create(principal.userId(), request)));
    }

    @Operation(summary = "프로젝트 상세",
            description = """
                    연결된 저장소·활성 명세서·최근 분석 상태를 함께 준다. 저장소의
                    accessStatus가 INACCESSIBLE이면 권한을 잃은 저장소다.

                    남의 프로젝트와 삭제된 프로젝트는 모두 404다. 403으로 구분하면 프로젝트의
                    존재 여부가 노출된다.""")
    @GetMapping("/{projectId}")
    public ResponseEntity<ApiResponse<ProjectDetailResponse>> get(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long projectId) {
        return ResponseEntity.ok(ApiResponse.success(
                projectService.get(principal.userId(), projectId)));
    }

    @Operation(summary = "프로젝트 수정",
            description = """
                    이름 변경, 보관(ACTIVE → ARCHIVED)과 복구(ARCHIVED → ACTIVE),
                    온보딩 단계 진행을 처리한다. DRAFT로 되돌리는 전이는 거부한다(PROJECT-005).
                    onboardingStep은 앞으로 가는 갱신만 반영되며 화면 라우팅에만 쓰인다.""")
    @PatchMapping("/{projectId}")
    public ResponseEntity<ApiResponse<ProjectDetailResponse>> update(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long projectId,
            @Valid @RequestBody ProjectUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                projectService.update(principal.userId(), projectId, request)));
    }

    @Operation(summary = "프로젝트 삭제",
            description = """
                    soft delete다. 진행 중이던 분석 작업은 CANCELLED로 바뀌고, 삭제된 프로젝트의
                    하위 리소스는 전부 404가 된다.""")
    @DeleteMapping("/{projectId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long projectId) {
        projectService.delete(principal.userId(), projectId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
