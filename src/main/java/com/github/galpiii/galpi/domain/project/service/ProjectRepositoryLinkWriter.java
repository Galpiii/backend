package com.github.galpiii.galpi.domain.project.service;

import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 저장소 연결의 쓰기 구간만 담당한다.
 *
 * <p>별도 빈으로 둔 이유는 트랜잭션 범위 때문이다. 연결 전 권한 재검증은 installation 수만큼
 * GitHub을 호출하는데, 그 전체를 트랜잭션으로 감싸면 외부 응답을 기다리는 동안 DB 커넥션을
 * 붙잡고 있게 된다. 조회는 트랜잭션 밖에서 끝내고 쓰기만 여기서 짧게 처리한다.
 * {@link com.github.galpiii.galpi.domain.github.service.GithubRepositorySnapshotWriter}와 같은 이유다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectRepositoryLinkWriter {

    private final ProjectRepository projectRepository;
    private final GithubRepositoryRepository repositoryRepository;

    /**
     * 소유권을 다시 확인하고 저장한다.
     *
     * <p>호출 쪽이 GitHub을 부르기 전에 이미 소유권을 봤지만 여기서 한 번 더 본다. 그 사이에
     * 프로젝트가 지워지거나 주인이 바뀔 수 있고, 외부 호출이 끼어 있어 그 틈이 짧지 않다.
     *
     * @param accessible GitHub에 직접 물어 확인한 {@code githubRepositoryId → 현재 모습}
     */
    @Transactional
    public List<GithubRepository> link(Long userId, Long projectId,
                                       Collection<Long> githubRepositoryIds,
                                       Map<Long, RepositorySnapshot> accessible) {
        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROJECT_NOT_FOUND));

        List<GithubRepository> already = repositoryRepository
                .findAllByProjectIdAndGithubRepositoryIdIn(project.getId(), githubRepositoryIds);
        if (!already.isEmpty()) {
            throw new ConflictException(ErrorCode.PROJECT_REPOSITORY_ALREADY_LINKED);
        }

        List<GithubRepository> linked = githubRepositoryIds.stream()
                .map(id -> GithubRepository.link(project, accessible.get(id)))
                .toList();

        return saveOrConflict(linked, projectId);
    }

    /**
     * 사전 조회와 저장 사이의 경쟁을 UNIQUE 제약으로 막는다.
     *
     * <p>같은 저장소를 두 요청이 동시에 연결하면 사전 조회는 둘 다 통과한다. 마지막 방어선은
     * {@code uk_repositories_project_github_repository}인데, 그대로 두면 제약 위반이 500이 된다.
     * 커밋까지 미루면 이 메서드 밖에서 터지므로 여기서 flush해 잡아 409로 바꾼다.
     */
    private List<GithubRepository> saveOrConflict(List<GithubRepository> linked, Long projectId) {
        try {
            return repositoryRepository.saveAllAndFlush(linked);
        } catch (DataIntegrityViolationException e) {
            log.info("[GitHub] 저장소 연결이 동시에 들어와 UNIQUE 제약에서 갈렸다 projectId={}", projectId);
            throw new ConflictException(ErrorCode.PROJECT_REPOSITORY_ALREADY_LINKED);
        }
    }
}
