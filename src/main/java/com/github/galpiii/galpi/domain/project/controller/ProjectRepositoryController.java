package com.github.galpiii.galpi.domain.project.controller;

import com.github.galpiii.galpi.domain.auth.jwt.AuthPrincipal;
import com.github.galpiii.galpi.domain.github.dto.SelectableRepositoryResponse;
import com.github.galpiii.galpi.domain.project.dto.LinkRepositoriesRequest;
import com.github.galpiii.galpi.domain.project.dto.LinkedRepositoryResponse;
import com.github.galpiii.galpi.domain.project.dto.ResolveRepositoryRequest;
import com.github.galpiii.galpi.domain.project.service.ProjectRepositoryService;
import com.github.galpiii.galpi.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "프로젝트 저장소")
@RestController
@RequiredArgsConstructor
@RequestMapping("/projects/{projectId}/repositories")
public class ProjectRepositoryController {

    private final ProjectRepositoryService projectRepositoryService;

    @Operation(summary = "연결된 저장소 목록",
            description = """
                    accessStatus가 INACCESSIBLE이면 권한을 잃은 저장소다. 다만 이 상태로 바꾸는
                    것은 Phase 1C의 수집 경로이므로, 지금은 항상 ACCESSIBLE로만 내려간다.
                    권한 회수를 실시간으로 반영하지 않는다.""")
    @GetMapping
    public ResponseEntity<ApiResponse<List<LinkedRepositoryResponse>>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long projectId) {
        return ResponseEntity.ok(ApiResponse.success(
                projectRepositoryService.list(principal.userId(), projectId)));
    }

    @Operation(summary = "저장소 연결",
            description = """
                    githubRepositoryId가 지금 이 사용자에게 실제로 보이는 저장소인지 서버에서
                    다시 확인한 뒤 저장한다. 서로 다른 installation의 저장소를 함께 보내도 된다.
                    이미 연결된 저장소가 섞여 있으면 409로 거부한다.""")
    @PostMapping
    public ResponseEntity<ApiResponse<List<LinkedRepositoryResponse>>> link(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long projectId,
            @Valid @RequestBody LinkRepositoriesRequest request) {
        return ResponseEntity.ok(ApiResponse.success(projectRepositoryService.link(
                principal.userId(), projectId, request.githubRepositoryIds())));
    }

    @Operation(summary = "URL로 저장소 찾기",
            description = """
                    목록에 뜨지 않는 저장소를 사용자가 URL로 직접 넣는 경로다. .git 접미사,
                    트레일링 슬래시, /tree/main 같은 경로가 붙어 있어도 정규화하며, GitHub
                    이외의 호스트는 거부한다(PROJECT-007).

                    확인만 하고 저장하지 않는다. 여기서 받은 githubRepositoryId를
                    POST /projects/{projectId}/repositories로 보내면 연결된다.

                    저장소가 없는 경우와 갈피에 권한이 없는 경우를 구분하지 않고 똑같이
                    PROJECT-008로 응답한다. 구분하면 비공개 저장소의 존재 여부가 노출된다.""")
    @PostMapping("/resolve")
    public ResponseEntity<ApiResponse<SelectableRepositoryResponse>> resolve(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long projectId,
            @Valid @RequestBody ResolveRepositoryRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                projectRepositoryService.resolve(principal.userId(), projectId, request.url())));
    }

    @Operation(summary = "저장소 연결 해제",
            description = "경로의 repositoryId는 갈피 내부 id다. GitHub 저장소 id가 아니다.")
    @DeleteMapping("/{repositoryId}")
    public ResponseEntity<ApiResponse<Void>> unlink(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long projectId,
            @PathVariable Long repositoryId) {
        projectRepositoryService.unlink(principal.userId(), projectId, repositoryId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
