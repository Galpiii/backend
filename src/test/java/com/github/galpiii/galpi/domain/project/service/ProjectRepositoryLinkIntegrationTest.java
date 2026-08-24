package com.github.galpiii.galpi.domain.project.service;

import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.github.service.GithubInstallationService;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * 실제 Postgres에 붙여 검증한다.
 *
 * <p>단위 테스트로는 확인할 수 없는 두 가지가 여기 있다. 트랜잭션 경계는 프록시가 있어야
 * 생기고, UNIQUE 제약 이름은 진짜 드라이버 예외에만 들어 있다. 두 가지 모두 이 PR의 핵심
 * 수정이라 mock으로 통과시키면 검증한 것이 없다.
 */
@DisplayName("저장소 연결 — 실제 Postgres")
class ProjectRepositoryLinkIntegrationTest extends IntegrationTestSupport {

    private static final long PERSONAL_INSTALLATION = 100L;
    private static final long REPOSITORY_ID = 1L;

    @Autowired
    private ProjectRepositoryService service;
    @Autowired
    private ProjectRepositoryLinkWriter linkWriter;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private GithubRepositoryRepository repositoryRepository;
    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private GithubInstallationService installationService;

    private Long userId;
    private Long projectId;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(
                User.ofGithub(System.nanoTime(), "wb", "wb", null, "https://avatar"));
        Project project = projectRepository.save(Project.create(user, "갈피"));
        userId = user.getId();
        projectId = project.getId();
    }

    private static RepositorySnapshot snapshot(long id, String fullName) {
        String owner = fullName.substring(0, fullName.indexOf('/'));
        String name = fullName.substring(fullName.indexOf('/') + 1);
        return new RepositorySnapshot(id, PERSONAL_INSTALLATION, owner, name, fullName, true,
                "main", "https://github.com/" + fullName);
    }

    private static Map<Long, RepositorySnapshot> accessible(RepositorySnapshot... snapshots) {
        Map<Long, RepositorySnapshot> map = new LinkedHashMap<>();
        for (RepositorySnapshot snapshot : snapshots) {
            map.put(snapshot.githubRepositoryId(), snapshot);
        }
        return map;
    }

    @Test
    @DisplayName("GitHub 조회는 트랜잭션 밖에서 끝난다 — 외부 응답을 기다리는 동안 커넥션을 잡지 않는다")
    void keepsGithubLookupOutsideTransaction() {
        AtomicBoolean insideTransaction = new AtomicBoolean(true);
        given(installationService.accessibleSnapshots(anyLong(), any())).willAnswer(invocation -> {
            insideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return accessible(snapshot(REPOSITORY_ID, "wb/notes"));
        });

        service.link(userId, projectId, List.of(REPOSITORY_ID));

        assertThat(insideTransaction).isFalse();
    }

    @Test
    @DisplayName("쓰기 구간은 트랜잭션 안이다 — 중간에 실패하면 아무것도 남지 않는다")
    void rollsBackTheWholeWrite() {
        // default_branch가 NOT NULL이라 두 번째 저장소에서 제약에 걸린다.
        RepositorySnapshot broken = new RepositorySnapshot(
                2L, PERSONAL_INSTALLATION, "wb", "broken", "wb/broken", false, null,
                "https://github.com/wb/broken");

        assertThatThrownBy(() -> linkWriter.link(userId, projectId, List.of(REPOSITORY_ID, 2L),
                accessible(snapshot(REPOSITORY_ID, "wb/notes"), broken)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repositoryRepository.findAllByProjectId(projectId)).isEmpty();
    }

    @Test
    @DisplayName("같은 저장소를 다시 연결해도 행이 늘지 않는다 — 사전 조회가 제약에 닿기 전에 걸러 낸다")
    void isIdempotentAcrossCalls() {
        Map<Long, RepositorySnapshot> accessible = accessible(snapshot(REPOSITORY_ID, "wb/notes"));
        linkWriter.link(userId, projectId, List.of(REPOSITORY_ID), accessible);

        List<GithubRepository> linked =
                linkWriter.link(userId, projectId, List.of(REPOSITORY_ID), accessible);

        assertThat(linked).extracting(GithubRepository::getGithubRepositoryId)
                .containsExactly(REPOSITORY_ID);
        assertThat(repositoryRepository.findAllByProjectId(projectId)).hasSize(1);
    }

    @Test
    @DisplayName("이미 연결된 것과 새 저장소를 함께 보내면 새 것만 추가된다")
    void addsOnlyTheMissingOnes() {
        linkWriter.link(userId, projectId, List.of(REPOSITORY_ID),
                accessible(snapshot(REPOSITORY_ID, "wb/notes")));

        List<GithubRepository> linked = linkWriter.link(userId, projectId,
                List.of(REPOSITORY_ID, 2L),
                accessible(snapshot(REPOSITORY_ID, "wb/notes"), snapshot(2L, "galpiii/backend")));

        assertThat(linked).extracting(GithubRepository::getGithubRepositoryId)
                .containsExactly(REPOSITORY_ID, 2L);
        assertThat(repositoryRepository.findAllByProjectId(projectId))
                .extracting(GithubRepository::getGithubRepositoryId)
                .containsExactlyInAnyOrder(REPOSITORY_ID, 2L);
    }

    /**
     * 사전 조회를 건너뛰고 제약에 직접 부딪혀 본다. 동시 요청일 때 남는 마지막 방어선이고,
     * writer가 409로 바꿀지 말지를 이 이름 하나로 판단하므로 마이그레이션과 코드가 같은
     * 이름을 보고 있는지 여기서 못박는다. 제약을 이름만 바꿔도 409가 조용히 500이 된다.
     */
    @Test
    @DisplayName("실제 UNIQUE 위반이 writer가 찾는 제약 이름을 달고 온다")
    void realViolationCarriesTheExpectedConstraintName() {
        linkWriter.link(userId, projectId, List.of(REPOSITORY_ID),
                accessible(snapshot(REPOSITORY_ID, "wb/notes")));

        Project project = projectRepository.findById(projectId).orElseThrow();

        assertThatThrownBy(() -> repositoryRepository.saveAndFlush(
                GithubRepository.link(project, snapshot(REPOSITORY_ID, "wb/notes"))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .cause().isInstanceOf(ConstraintViolationException.class)
                .extracting(cause -> ((ConstraintViolationException) cause).getConstraintName())
                .asString()
                .isEqualToIgnoringCase("uk_repositories_project_github_repository");
    }
}
