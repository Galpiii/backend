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
import org.hibernate.exception.ConstraintViolationException;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectRepositoryLinkWriter {

    /** V3 마이그레이션의 제약 이름. 바꾸면 409가 조용히 500으로 돌아간다. */
    private static final String UNIQUE_PROJECT_REPOSITORY =
            "uk_repositories_project_github_repository";

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
        Project project = projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROJECT_NOT_FOUND));

        List<GithubRepository> already = repositoryRepository
                .findAllByProjectIdAndGithubRepositoryIdIn(project.getId(), githubRepositoryIds);
        if (!already.isEmpty()) {
            throw new ConflictException(ErrorCode.PROJECT_REPOSITORY_ALREADY_LINKED);
        }

        List<GithubRepository> linked = githubRepositoryIds.stream()
                .map(id -> GithubRepository.link(project, accessible.get(id)))
                .toList();

        List<GithubRepository> saved = saveOrConflict(linked, projectId);

        // 저장소가 붙은 순간 프로젝트는 더 이상 DRAFT가 아니다. 위저드도 ③ 단계로 넘어간다.
        // 마지막 저장소를 빼도 DRAFT로 되돌리지는 않는다 — 되돌릴 수 있는 전이가 아니다.
        project.markRepositoriesLinked();

        return saved;
    }

    /**
     * 사전 조회와 저장 사이의 경쟁을 UNIQUE 제약으로 막는다.
     *
     * <p>같은 저장소를 두 요청이 동시에 연결하면 사전 조회는 둘 다 통과한다. 마지막 방어선은
     * {@code uk_repositories_project_github_repository}인데, 그대로 두면 제약 위반이 500이 된다.
     * 커밋까지 미루면 이 메서드 밖에서 터지므로 여기서 flush해 잡아 409로 바꾼다.
     *
     * <p>바꾸는 대상은 그 제약 하나뿐이다. 여기서 나올 수 있는 무결성 위반은 이것 말고도
     * 프로젝트 동시 삭제로 인한 FK 위반, GitHub 응답의 빈 값으로 인한 NOT NULL 위반,
     * 앞으로 늘어날 제약이 있다. 그것까지 "이미 추가된 저장소"로 바꾸면 서버 결함이 사용자
     * 실수로 둔갑해 조용히 묻힌다. 나머지는 그대로 올려보내 500으로 드러내는 것이 맞다.
     */
    private List<GithubRepository> saveOrConflict(List<GithubRepository> linked, Long projectId) {
        try {
            return repositoryRepository.saveAllAndFlush(linked);
        } catch (DataIntegrityViolationException e) {
            if (!isAlreadyLinked(e)) {
                throw e;
            }
            log.info("[GitHub] 저장소 연결이 동시에 들어와 UNIQUE 제약에서 갈렸다 projectId={}", projectId);
            throw new ConflictException(ErrorCode.PROJECT_REPOSITORY_ALREADY_LINKED);
        }
    }

    /** 제약 이름으로만 판단한다. 드라이버 메시지 문구는 버전에 따라 달라진다. */
    private static boolean isAlreadyLinked(DataIntegrityViolationException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return UNIQUE_PROJECT_REPOSITORY.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
