package com.github.galpiii.galpi.domain.project.service;

import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.github.service.GithubInstallationService;
import com.github.galpiii.galpi.domain.project.dto.LinkedRepositoryResponse;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ForbiddenException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 프로젝트와 GitHub 저장소의 연결.
 *
 * <p>한 프로젝트에 서로 다른 installation의 저장소가 섞일 수 있다. 개인 계정 저장소와 조직 A·B의
 * 저장소를 한 프로젝트로 묶는 것이 기본 사용 방식이므로, installation 단위로 묶어 제약하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectRepositoryService {

    private final ProjectRepository projectRepository;
    private final GithubRepositoryRepository repositoryRepository;
    private final GithubInstallationService installationService;
    private final ProjectRepositoryLinkWriter linkWriter;

    @Transactional(readOnly = true)
    public List<LinkedRepositoryResponse> list(Long userId, Long projectId) {
        Project project = ownedProject(userId, projectId);
        return repositoryRepository.findAllByProjectId(project.getId()).stream()
                .map(LinkedRepositoryResponse::from)
                .toList();
    }

    /**
     * 저장소를 프로젝트에 연결한다.
     *
     * <p>프론트가 보낸 {@code githubRepositoryId}는 믿지 않는다. 지금 이 사용자가 실제로
     * 접근 가능한 목록을 GitHub에서 새로 받아 대조하고, 없는 id가 하나라도 있으면 거부한다.
     * 저장하는 값도 요청 본문이 아니라 그 조회 결과에서 가져온다.
     *
     * <p>이 메서드에는 트랜잭션을 걸지 않는다. 권한 재검증이 installation 수만큼 GitHub을
     * 호출하므로, 전체를 감싸면 외부 응답을 기다리는 내내 DB 커넥션이 묶인다. 쓰기는
     * {@link ProjectRepositoryLinkWriter}가 짧게 처리한다.
     */
    public List<LinkedRepositoryResponse> link(Long userId, Long projectId,
                                               List<Long> githubRepositoryIds) {
        // GitHub을 부르기 전에 소유권부터 본다. 남의 프로젝트면 외부 호출 없이 여기서 끝난다.
        ownedProject(userId, projectId);
        Set<Long> requested = new LinkedHashSet<>(githubRepositoryIds);

        Map<Long, RepositorySnapshot> accessible = installationService.accessibleSnapshots(userId);
        List<Long> denied = requested.stream()
                .filter(id -> !accessible.containsKey(id))
                .toList();
        if (!denied.isEmpty()) {
            log.warn("[GitHub] 접근 권한이 없는 저장소 연결 시도 userId={} projectId={} count={}",
                    userId, projectId, denied.size());
            throw new ForbiddenException(ErrorCode.GITHUB_REPOSITORY_ACCESS_DENIED);
        }

        return linkWriter.link(userId, projectId, requested, accessible).stream()
                .map(LinkedRepositoryResponse::from)
                .toList();
    }

    /**
     * 연결을 끊는다.
     *
     * <p>지금은 물리 삭제다. 1C에서 {@code analysis_run_repositories}가 이 행을 FK로 참조하게
     * 되면 과거 분석 이력이 함께 끊기므로, 그때 소프트 삭제로 바꿀지 다시 판단해야 한다.
     */
    @Transactional
    public void unlink(Long userId, Long projectId, Long repositoryId) {
        Project project = ownedProject(userId, projectId);
        GithubRepository repository = repositoryRepository
                .findByIdAndProjectId(repositoryId, project.getId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROJECT_REPOSITORY_NOT_FOUND));

        repositoryRepository.delete(repository);
    }

    private Project ownedProject(Long userId, Long projectId) {
        return projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROJECT_NOT_FOUND));
    }
}
