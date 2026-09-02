package com.github.galpiii.galpi.domain.project.service;

import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRun;
import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunRepository;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.project.config.ProjectProperties;
import com.github.galpiii.galpi.domain.project.dto.LastAnalysisResponse;
import com.github.galpiii.galpi.domain.project.dto.LinkedRepositoryResponse;
import com.github.galpiii.galpi.domain.project.dto.ProjectCreateRequest;
import com.github.galpiii.galpi.domain.project.dto.ProjectCreatedResponse;
import com.github.galpiii.galpi.domain.project.dto.ProjectDetailResponse;
import com.github.galpiii.galpi.domain.project.dto.ProjectListResponse;
import com.github.galpiii.galpi.domain.project.dto.ProjectSort;
import com.github.galpiii.galpi.domain.project.dto.ProjectUpdateRequest;
import com.github.galpiii.galpi.domain.project.dto.SpecDocumentSummaryResponse;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.entity.ProjectStatus;
import com.github.galpiii.galpi.domain.project.event.ProjectDeletedEvent;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 프로젝트 자체의 CRUD.
 *
 * <p>모든 조회는 {@code owner_id}와 {@code deleted_at IS NULL}로 함께 좁힌다. 남의 프로젝트와
 * 삭제된 프로젝트는 모두 404다 — 403으로 구분하면 프로젝트 id를 하나씩 넣어 보는 것만으로
 * 남이 무엇을 가지고 있는지 알 수 있다.
 *
 * <p>GitHub 연결 상태를 보지 않는다. user access token이 만료돼도 프로젝트는 만들고 고치고
 * 지울 수 있어야 하고, 재인증은 저장소를 실제로 다루는 단계에서 요구한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    /** 목록 기본값. 보관한 프로젝트는 빼고 보여 준다. */
    private static final Collection<ProjectStatus> DEFAULT_LIST_STATUSES =
            List.of(ProjectStatus.DRAFT, ProjectStatus.ACTIVE);

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final GithubRepositoryRepository repositoryRepository;
    private final SpecDocumentRepository specDocumentRepository;
    // 프로젝트를 지우면 진행 중인 분석도 함께 멈춰야 한다. 두 가지가 한 트랜잭션에 있어야
    // 삭제만 되고 작업은 계속 도는 상태가 생기지 않는다.
    private final AnalysisRunRepository analysisRunRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ProjectProperties properties;

    @Transactional(readOnly = true)
    public ProjectListResponse list(Long userId, ProjectStatus status, ProjectSort sort,
                                    Integer page, Integer size) {
        Collection<ProjectStatus> statuses =
                status == null ? DEFAULT_LIST_STATUSES : List.of(status);
        ProjectSort resolvedSort = sort == null ? ProjectSort.UPDATED_AT : sort;

        PageRequest pageRequest = PageRequest.of(
                page == null || page < 0 ? 0 : page,
                resolvePageSize(size),
                resolvedSort.toSort());

        return ProjectListResponse.from(
                projectRepository.findSummaries(userId, statuses, pageRequest));
    }

    /**
     * 위저드 ① 단계.
     *
     * <p>명세서 없이도 만들어진다. "명세서는 나중에 등록"으로 건너뛴 사용자도 여기서 만들어진
     * 프로젝트를 가지고 ② 단계로 간다.
     */
    @Transactional
    public ProjectCreatedResponse create(Long userId, ProjectCreateRequest request) {
        long owned = projectRepository.countByOwnerIdAndDeletedAtIsNull(userId);
        if (owned >= properties.maxPerUser()) {
            log.warn("[프로젝트] 개수 상한에 걸렸다 userId={} owned={} limit={}",
                    userId, owned, properties.maxPerUser());
            throw new ConflictException(ErrorCode.PROJECT_LIMIT_EXCEEDED);
        }

        // 소유자는 인증된 사용자다. 요청 본문의 값을 쓰지 않는다.
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHORIZED));

        Project project = projectRepository.save(Project.create(owner, request.name()));
        log.info("[프로젝트] 생성 projectId={} userId={}", project.getId(), userId);
        return ProjectCreatedResponse.from(project);
    }

    @Transactional(readOnly = true)
    public ProjectDetailResponse get(Long userId, Long projectId) {
        Project project = requireOwned(userId, projectId);

        List<LinkedRepositoryResponse> repositories =
                repositoryRepository.findAllByProjectId(projectId).stream()
                        .map(LinkedRepositoryResponse::from)
                        .toList();

        return ProjectDetailResponse.of(project, repositories, specDocument(project),
                lastAnalysis(project));
    }

    @Transactional
    public ProjectDetailResponse update(Long userId, Long projectId,
                                        ProjectUpdateRequest request) {
        if (request.isEmpty()) {
            throw new BadRequestException(ErrorCode.PROJECT_UPDATE_EMPTY);
        }
        Project project = requireOwned(userId, projectId);

        if (request.name() != null) {
            project.rename(request.name());
        }
        if (request.status() != null) {
            changeStatus(project, request.status());
        }
        if (request.onboardingStep() != null) {
            project.advanceOnboardingStep(request.onboardingStep());
        }

        // @LastModifiedDate는 flush 시점에 채워진다. 먼저 응답을 만들면 수정 전 시각이 그대로
        // 나가고, 목록 정렬 기준이 updatedAt이라 프론트가 방금 받은 값으로 화면을 갱신하면
        // 순서가 어긋난다.
        projectRepository.flush();

        List<LinkedRepositoryResponse> repositories =
                repositoryRepository.findAllByProjectId(projectId).stream()
                        .map(LinkedRepositoryResponse::from)
                        .toList();
        return ProjectDetailResponse.of(project, repositories, specDocument(project),
                lastAnalysis(project));
    }

    /**
     * soft delete.
     *
     * <p>물리 삭제하지 않는 이유는 매달린 것이 많아서다 — 저장소·분석 이력·명세서·기능이 모두
     * FK로 연결돼 있고, 연쇄 삭제는 되돌릴 수 없다.
     *
     * <p>진행 중인 분석은 여기서 {@code CANCELLED}로 바꾼다. 이미 워커가 집어 간 작업은 이
     * 갱신만으로 멈추지 않으므로, 워커가 저장소 사이 체크포인트에서 다시 확인한다.
     */
    @Transactional
    public void delete(Long userId, Long projectId) {
        Project project = requireOwned(userId, projectId);
        project.softDelete();

        int cancelled = analysisRunRepository.cancelInFlight(projectId, OffsetDateTime.now());
        eventPublisher.publishEvent(new ProjectDeletedEvent(projectId));
        log.info("[프로젝트] 삭제 projectId={} userId={} cancelledRuns={}",
                projectId, userId, cancelled);
    }

    private void changeStatus(Project project, ProjectStatus target) {
        if (!project.getStatus().allowsTransitionTo(target)) {
            log.warn("[프로젝트] 허용되지 않은 상태 전이 projectId={} {} -> {}",
                    project.getId(), project.getStatus(), target);
            throw new BadRequestException(ErrorCode.PROJECT_STATUS_TRANSITION_NOT_ALLOWED);
        }
        project.changeStatus(target);
    }

    private SpecDocumentSummaryResponse specDocument(Project project) {
        if (project.getActiveSpecDocumentId() == null) {
            return null;
        }
        return specDocumentRepository.findById(project.getActiveSpecDocumentId())
                .map(SpecDocumentSummaryResponse::from)
                .orElse(null);
    }

    private LastAnalysisResponse lastAnalysis(Project project) {
        if (project.getLastAnalysisRunId() == null) {
            return null;
        }
        return analysisRunRepository.findById(project.getLastAnalysisRunId())
                .map(ProjectService::toLastAnalysis)
                .orElse(null);
    }

    private static LastAnalysisResponse toLastAnalysis(AnalysisRun run) {
        return LastAnalysisResponse.of(run.getId(), run.getStatus(), run.getCreatedAt(),
                run.getFinishedAt());
    }

    private int resolvePageSize(Integer size) {
        if (size == null || size < 1) {
            return properties.defaultPageSize();
        }
        return Math.min(size, properties.maxPageSize());
    }

    private Project requireOwned(Long userId, Long projectId) {
        return projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROJECT_NOT_FOUND));
    }
}
