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
import java.util.LinkedHashMap;
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
     * <p><b>이미 연결된 저장소가 섞여 있어도 거부하지 않는다.</b> 같은 id가 요청 안에 중복으로
     * 와도 한 번만 저장하는 것과 같은 이유다 — 사용자가 고친다고 달라질 것이 없는 요청이고,
     * 409로 돌려보내면 프론트가 화면의 선택 상태 전체가 아니라 새로 고른 것만 골라 보내야 한다.
     * 그 규칙을 흘리면 저장소를 두 번째로 추가하는 순간 조용히 실패한다.
     *
     * @param accessible GitHub에 직접 물어 확인한 {@code githubRepositoryId → 현재 모습}
     * @return 요청한 저장소의 지금 모습. 이미 있던 것과 새로 붙인 것을 요청 순서대로 함께 담는다
     */
    @Transactional
    public List<GithubRepository> link(Long userId, Long projectId,
                                       Collection<Long> githubRepositoryIds,
                                       Map<Long, RepositorySnapshot> accessible) {
        Project project = projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROJECT_NOT_FOUND));

        Map<Long, GithubRepository> linked = new LinkedHashMap<>();
        for (GithubRepository existing : repositoryRepository
                .findAllForRelink(project.getId(), githubRepositoryIds)) {
            // 끊겨 있던 행이면 여기서 되살아난다. 새로 만들지 않는 덕분에 끊기 전에 수집해 둔
            // PR과 분석 이력이 그대로 이어진다.
            //
            // 방금 GitHub에 물어 확인한 값으로 갱신도 함께 한다. 이름이 바뀐 저장소를 옛 이름
            // 그대로 돌려주지 않고, 수집 경로가 INACCESSIBLE로 표시해 둔 저장소는 여기서
            // 풀린다 — 다시 보인다는 것 자체가 접근 권한이 돌아왔다는 뜻이다.
            existing.relink(accessible.get(existing.getGithubRepositoryId()));
            linked.put(existing.getGithubRepositoryId(), existing);
        }

        List<GithubRepository> added = githubRepositoryIds.stream()
                .filter(id -> !linked.containsKey(id))
                .map(id -> GithubRepository.link(project, accessible.get(id)))
                .toList();
        for (GithubRepository saved : saveOrConflict(added, projectId)) {
            linked.put(saved.getGithubRepositoryId(), saved);
        }

        // 저장소가 붙은 순간 프로젝트는 더 이상 DRAFT가 아니다. 위저드도 ③ 단계로 넘어간다.
        // 마지막 저장소를 빼도 DRAFT로 되돌리지는 않는다 — 되돌릴 수 있는 전이가 아니다.
        // 새로 붙인 것이 없어도 그대로 부른다. 앞으로만 움직이는 전이라 다시 불러도 같다.
        project.markRepositoriesLinked();

        // 새로 붙인 것과 이미 있던 것을 구분해 내리지 않는다. 부른 쪽이 알아야 하는 것은
        // "요청한 저장소가 지금 이 프로젝트에 연결돼 있다"는 결과뿐이다.
        return githubRepositoryIds.stream().map(linked::get).toList();
    }

    /**
     * 사전 조회와 저장 사이의 경쟁을 UNIQUE 제약으로 막는다.
     *
     * <p>같은 저장소를 두 요청이 동시에 연결하면 사전 조회는 둘 다 통과한다. 마지막 방어선은
     * {@code uk_repositories_project_github_repository}인데, 그대로 두면 제약 위반이 500이 된다.
     * 커밋까지 미루면 이 메서드 밖에서 터지므로 여기서 flush해 잡아 409로 바꾼다.
     *
     * <p>연결이 멱등해진 뒤로 이 409는 <b>진짜 동시 요청에서만</b> 나온다. 배치 저장이 제약에
     * 걸리면 영속성 컨텍스트가 깨져 같은 트랜잭션 안에서 다시 읽을 수 없으므로 여기서 되살리지
     * 않는다. 대신 클라이언트가 같은 요청을 그대로 다시 보내면 이번에는 사전 조회가 상대의
     * 커밋을 보고 걸러 내므로 성공한다 — 409가 "다시 보내라"는 신호로 정직하게 동작한다.
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
