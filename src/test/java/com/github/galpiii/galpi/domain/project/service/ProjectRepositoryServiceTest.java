package com.github.galpiii.galpi.domain.project.service;

import com.github.galpiii.galpi.domain.github.client.dto.GithubRepositoryResponse;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.entity.RepositoryAccessStatus;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.exception.GithubReauthRequiredException;
import com.github.galpiii.galpi.domain.github.service.GithubInstallationService;
import com.github.galpiii.galpi.domain.github.service.GithubUserTokenService;
import com.github.galpiii.galpi.domain.github.support.GithubRepositoryUrlParser;
import com.github.galpiii.galpi.domain.project.dto.LinkedRepositoryResponse;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.event.ProjectRepositoryUnlinkedEvent;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.ForbiddenException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProjectRepositoryService — 프로젝트-저장소 연결")
class ProjectRepositoryServiceTest {

    private static final long USER_ID = 7L;
    private static final long PROJECT_ID = 3L;
    private static final long PERSONAL_INSTALLATION = 100L;
    private static final long ORG_INSTALLATION = 200L;

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private GithubRepositoryRepository repositoryRepository;
    @Mock
    private GithubInstallationService installationService;
    @Mock
    private GithubUserTokenService userTokenService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ProjectRepositoryService service;
    private Project project;

    @BeforeEach
    void setUp() {
        // 쓰기 구간만 분리한 협력자라 진짜 객체를 쓴다. @Transactional은 프록시 밖이라 동작하지
        // 않지만, 검증하려는 것은 트랜잭션 경계가 아니라 저장 전 순서다.
        service = new ProjectRepositoryService(
                projectRepository, repositoryRepository, installationService,
                new ProjectRepositoryLinkWriter(projectRepository, repositoryRepository),
                new GithubRepositoryUrlParser(githubProperties()),
                userTokenService, eventPublisher);
        project = Project.create(mock(User.class), "갈피");
        given(projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(PROJECT_ID, USER_ID)).willReturn(Optional.of(project));
        given(repositoryRepository.findAllForRelink(any(), any()))
                .willReturn(List.of());
        given(repositoryRepository.saveAllAndFlush(any()))
                .willAnswer(invocation -> List.copyOf(invocation.getArgument(0)));
    }

    private static GithubAppProperties githubProperties() {
        return new GithubAppProperties(
                "12345", "galpi-app", "Iv1.client", "secret", "pem", "https://api.galpi.dev",
                "2022-11-28", "https://api.github.com", "https://github.com", "Galpi",
                List.of("https://galpi.dev"), "https://galpi.dev/auth/callback",
                Duration.ofSeconds(5), Duration.ofSeconds(15), 2, 10);
    }

    private static RepositorySnapshot snapshot(long id, long installationId, String fullName) {
        String owner = fullName.substring(0, fullName.indexOf('/'));
        String name = fullName.substring(fullName.indexOf('/') + 1);
        return new RepositorySnapshot(id, installationId, owner, name, fullName, true, "main",
                "https://github.com/" + fullName);
    }

    /**
     * 실제 위반과 같은 원인 체인을 만든다. 제약 이름은 메시지가 아니라 이 예외에서 읽는다.
     */
    private static DataIntegrityViolationException integrityViolation(String constraintName) {
        return new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException("constraint violation",
                        new SQLException("duplicate key value violates unique constraint"),
                        constraintName));
    }

    private static Map<Long, RepositorySnapshot> accessible(RepositorySnapshot... snapshots) {
        Map<Long, RepositorySnapshot> map = new LinkedHashMap<>();
        for (RepositorySnapshot snapshot : snapshots) {
            map.put(snapshot.githubRepositoryId(), snapshot);
        }
        return map;
    }

    @Nested
    @DisplayName("연결")
    class Link {

        @Test
        @DisplayName("서로 다른 installation의 저장소를 한 프로젝트에 함께 연결한다")
        void linksAcrossInstallations() {
            given(installationService.accessibleSnapshots(eq(USER_ID), any())).willReturn(accessible(
                    snapshot(1L, PERSONAL_INSTALLATION, "wb/notes"),
                    snapshot(2L, ORG_INSTALLATION, "galpiii/backend")));

            List<LinkedRepositoryResponse> linked =
                    service.link(USER_ID, PROJECT_ID, List.of(1L, 2L));

            assertThat(linked).hasSize(2)
                    .extracting(LinkedRepositoryResponse::installationId)
                    .containsExactly(PERSONAL_INSTALLATION, ORG_INSTALLATION);
        }

        @Test
        @DisplayName("접근 권한이 없는 저장소 id는 저장 전에 거부한다")
        void rejectsInaccessibleRepository() {
            given(installationService.accessibleSnapshots(eq(USER_ID), any()))
                    .willReturn(accessible(snapshot(1L, PERSONAL_INSTALLATION, "wb/notes")));

            assertThatThrownBy(() -> service.link(USER_ID, PROJECT_ID, List.of(1L, 999L)))
                    .isInstanceOf(ForbiddenException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GITHUB_REPOSITORY_ACCESS_DENIED);

            verify(repositoryRepository, never()).saveAllAndFlush(any());
        }

        @Test
        @DisplayName("저장 값은 요청 본문이 아니라 GitHub 조회 결과에서 가져온다")
        void storesServerSideSnapshot() {
            given(installationService.accessibleSnapshots(eq(USER_ID), any()))
                    .willReturn(accessible(snapshot(1L, PERSONAL_INSTALLATION, "wb/notes")));

            LinkedRepositoryResponse linked =
                    service.link(USER_ID, PROJECT_ID, List.of(1L)).getFirst();

            assertThat(linked.fullName()).isEqualTo("wb/notes");
            assertThat(linked.owner()).isEqualTo("wb");
            assertThat(linked.defaultBranch()).isEqualTo("main");
            assertThat(linked.accessStatus()).isEqualTo(RepositoryAccessStatus.ACCESSIBLE.name());
        }

        @Test
        @DisplayName("이미 추가된 저장소가 섞여 있어도 나머지를 붙인다 — 화면의 선택 상태를 통째로 보내도 된다")
        @SuppressWarnings("unchecked")
        void skipsAlreadyLinkedAndAddsTheRest() {
            given(installationService.accessibleSnapshots(eq(USER_ID), any())).willReturn(accessible(
                    snapshot(1L, PERSONAL_INSTALLATION, "wb/notes"),
                    snapshot(2L, ORG_INSTALLATION, "galpiii/backend")));
            given(repositoryRepository.findAllForRelink(any(), any()))
                    .willReturn(List.of(GithubRepository.link(
                            project, snapshot(1L, PERSONAL_INSTALLATION, "wb/notes"))));

            List<LinkedRepositoryResponse> linked =
                    service.link(USER_ID, PROJECT_ID, List.of(1L, 2L));

            // 응답은 요청한 순서 그대로, 이미 있던 것과 새로 붙인 것을 함께 담는다.
            assertThat(linked).extracting(LinkedRepositoryResponse::githubRepositoryId)
                    .containsExactly(1L, 2L);

            // 이미 있던 저장소를 다시 저장하지 않는다.
            ArgumentCaptor<List<GithubRepository>> saved = ArgumentCaptor.forClass(List.class);
            verify(repositoryRepository).saveAllAndFlush(saved.capture());
            assertThat(saved.getValue())
                    .extracting(GithubRepository::getGithubRepositoryId)
                    .containsExactly(2L);
        }

        @Test
        @DisplayName("이미 연결된 저장소만 다시 보내도 성공한다 — 같은 요청을 두 번 보내도 결과가 같다")
        void isIdempotentOnReplay() {
            given(installationService.accessibleSnapshots(eq(USER_ID), any()))
                    .willReturn(accessible(snapshot(1L, PERSONAL_INSTALLATION, "wb/notes")));
            given(repositoryRepository.findAllForRelink(any(), any()))
                    .willReturn(List.of(GithubRepository.link(
                            project, snapshot(1L, PERSONAL_INSTALLATION, "wb/notes"))));

            List<LinkedRepositoryResponse> linked = service.link(USER_ID, PROJECT_ID, List.of(1L));

            assertThat(linked).extracting(LinkedRepositoryResponse::githubRepositoryId)
                    .containsExactly(1L);
        }

        @Test
        @DisplayName("이미 연결된 저장소도 최신 스냅샷으로 갱신한다 — 이름 변경과 접근 권한 회복이 반영된다")
        void refreshesAlreadyLinkedRepository() {
            GithubRepository stale = GithubRepository.link(
                    project, snapshot(1L, PERSONAL_INSTALLATION, "wb/old-name"));
            stale.markInaccessible();

            given(installationService.accessibleSnapshots(eq(USER_ID), any()))
                    .willReturn(accessible(snapshot(1L, PERSONAL_INSTALLATION, "wb/renamed")));
            given(repositoryRepository.findAllForRelink(any(), any()))
                    .willReturn(List.of(stale));

            LinkedRepositoryResponse linked =
                    service.link(USER_ID, PROJECT_ID, List.of(1L)).getFirst();

            assertThat(linked.fullName()).isEqualTo("wb/renamed");
            assertThat(linked.name()).isEqualTo("renamed");
            assertThat(linked.accessStatus()).isEqualTo(RepositoryAccessStatus.ACCESSIBLE.name());
        }

        @Test
        @DisplayName("같은 id가 중복으로 와도 한 번만 저장한다")
        void deduplicatesRequestedIds() {
            given(installationService.accessibleSnapshots(eq(USER_ID), any()))
                    .willReturn(accessible(snapshot(1L, PERSONAL_INSTALLATION, "wb/notes")));

            assertThat(service.link(USER_ID, PROJECT_ID, List.of(1L, 1L, 1L))).hasSize(1);
        }

        @Test
        @DisplayName("남의 프로젝트에는 연결할 수 없다")
        void rejectsForeignProject() {
            given(projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(PROJECT_ID, USER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.link(USER_ID, PROJECT_ID, List.of(1L)))
                    .isInstanceOf(NotFoundException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);

            verify(installationService, never()).accessibleSnapshots(anyLong(), any());
        }

        @Test
        @DisplayName("GitHub 조회 중에 프로젝트를 잃으면 저장하지 않는다 — 소유권을 저장 직전 다시 본다")
        void rechecksOwnershipBeforeSaving() {
            given(installationService.accessibleSnapshots(eq(USER_ID), any()))
                    .willReturn(accessible(snapshot(1L, PERSONAL_INSTALLATION, "wb/notes")));
            given(projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(PROJECT_ID, USER_ID))
                    .willReturn(Optional.of(project), Optional.empty());

            assertThatThrownBy(() -> service.link(USER_ID, PROJECT_ID, List.of(1L)))
                    .isInstanceOf(NotFoundException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);

            verify(projectRepository, times(2)).findByIdAndOwnerIdAndDeletedAtIsNull(PROJECT_ID, USER_ID);
            verify(repositoryRepository, never()).saveAllAndFlush(any());
        }

        @Test
        @DisplayName("동시 연결로 UNIQUE 제약에 걸리면 500이 아니라 409로 안내한다")
        void translatesUniqueViolationToConflict() {
            given(installationService.accessibleSnapshots(eq(USER_ID), any()))
                    .willReturn(accessible(snapshot(1L, PERSONAL_INSTALLATION, "wb/notes")));
            willThrow(integrityViolation("uk_repositories_project_github_repository"))
                    .given(repositoryRepository).saveAllAndFlush(any());

            assertThatThrownBy(() -> service.link(USER_ID, PROJECT_ID, List.of(1L)))
                    .isInstanceOf(ConflictException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.PROJECT_REPOSITORY_ALREADY_LINKED);
        }

        @Test
        @DisplayName("다른 제약 위반은 409로 덮지 않는다 — 서버 결함이 사용자 충돌로 둔갑하면 안 된다")
        void doesNotMaskOtherConstraintViolations() {
            given(installationService.accessibleSnapshots(eq(USER_ID), any()))
                    .willReturn(accessible(snapshot(1L, PERSONAL_INSTALLATION, "wb/notes")));
            willThrow(integrityViolation("fk_repositories_project"))
                    .given(repositoryRepository).saveAllAndFlush(any());

            assertThatThrownBy(() -> service.link(USER_ID, PROJECT_ID, List.of(1L)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("제약 이름을 알 수 없는 무결성 위반도 그대로 올려보낸다")
        void doesNotMaskUnnamedViolations() {
            given(installationService.accessibleSnapshots(eq(USER_ID), any()))
                    .willReturn(accessible(snapshot(1L, PERSONAL_INSTALLATION, "wb/notes")));
            willThrow(new DataIntegrityViolationException("uk_repositories_project_github_repository"))
                    .given(repositoryRepository).saveAllAndFlush(any());

            assertThatThrownBy(() -> service.link(USER_ID, PROJECT_ID, List.of(1L)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("연결 해제")
    class Unlink {

        @BeforeEach
        void connected() {
            given(userTokenService.isValid(USER_ID)).willReturn(true);
        }

        @Test
        @DisplayName("프로젝트에 속한 저장소만 끊을 수 있다")
        void unlinksOwnRepository() {
            GithubRepository repository = GithubRepository.link(
                    project, snapshot(1L, PERSONAL_INSTALLATION, "wb/notes"));
            given(repositoryRepository.findByIdAndProjectId(any(), any()))
                    .willReturn(Optional.of(repository));

            service.unlink(USER_ID, PROJECT_ID, 55L);

            assertThat(repository.isUnlinked()).isTrue();
            verify(eventPublisher).publishEvent(new ProjectRepositoryUnlinkedEvent(55L));
        }

        @Test
        @DisplayName("행을 지우지 않는다 — 지우면 PR과 분석 이력이 CASCADE로 함께 사라진다")
        void neverDeletesTheRow() {
            GithubRepository repository = GithubRepository.link(
                    project, snapshot(1L, PERSONAL_INSTALLATION, "wb/notes"));
            given(repositoryRepository.findByIdAndProjectId(any(), any()))
                    .willReturn(Optional.of(repository));

            service.unlink(USER_ID, PROJECT_ID, 55L);

            verify(repositoryRepository, never()).delete(any());
            verify(repositoryRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("다른 프로젝트의 저장소 id는 찾지 못한다")
        void rejectsRepositoryOfAnotherProject() {
            given(repositoryRepository.findByIdAndProjectId(any(), any())).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.unlink(USER_ID, PROJECT_ID, 55L))
                    .isInstanceOf(NotFoundException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_REPOSITORY_NOT_FOUND);
        }

        @Test
        @DisplayName("GitHub 연결이 끊겼으면 저장소를 지우지 않는다 — 연결이 없는 동안은 조회만 허용한다")
        void rejectsWhileDisconnected() {
            given(userTokenService.isValid(USER_ID)).willReturn(false);

            assertThatThrownBy(() -> service.unlink(USER_ID, PROJECT_ID, 55L))
                    .isInstanceOf(GithubReauthRequiredException.class);

            verify(repositoryRepository, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("URL로 저장소 찾기")
    class Resolve {

        private GithubRepositoryResponse githubRepository(long id, String fullName) {
            String owner = fullName.substring(0, fullName.indexOf('/'));
            String name = fullName.substring(fullName.indexOf('/') + 1);
            return new GithubRepositoryResponse(id, name, fullName,
                    new GithubRepositoryResponse.Owner(1L, owner, "User"), true, "main",
                    "https://github.com/" + fullName, Map.of("pull", true),
                    "설명", "Java", OffsetDateTime.parse("2026-08-18T00:00:00Z"));
        }

        @Test
        @DisplayName("접근 가능한 저장소면 선택 목록에 넣을 정보를 돌려준다")
        void resolvesAccessibleRepository() {
            given(installationService.findAccessibleRepository(USER_ID, "galpiii", "backend"))
                    .willReturn(Optional.of(githubRepository(1L, "galpiii/backend")));

            assertThat(service.resolve(USER_ID, PROJECT_ID,
                    "https://github.com/galpiii/backend.git"))
                    .satisfies(response -> {
                        assertThat(response.githubRepositoryId()).isEqualTo(1L);
                        assertThat(response.fullName()).isEqualTo("galpiii/backend");
                        assertThat(response.linked()).isFalse();
                        assertThat(response.language()).isEqualTo("Java");
                    });
        }

        @Test
        @DisplayName("GitHub 저장소 URL이 아니면 GitHub을 부르지도 않는다")
        void rejectsInvalidUrl() {
            assertThatThrownBy(() -> service.resolve(USER_ID, PROJECT_ID,
                    "https://gitlab.com/galpiii/backend"))
                    .isInstanceOf(BadRequestException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.PROJECT_REPOSITORY_URL_INVALID);

            verify(installationService, never())
                    .findAccessibleRepository(anyLong(), any(), any());
        }

        @Test
        @DisplayName("없는 저장소와 권한 없는 비공개 저장소의 응답이 같다")
        void doesNotDistinguishMissingFromForbidden() {
            given(installationService.findAccessibleRepository(anyLong(), any(), any()))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolve(USER_ID, PROJECT_ID,
                    "https://github.com/galpiii/does-not-exist"))
                    .isInstanceOf(NotFoundException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.PROJECT_REPOSITORY_NOT_ACCESSIBLE);

            assertThatThrownBy(() -> service.resolve(USER_ID, PROJECT_ID,
                    "https://github.com/someone/private-repo"))
                    .isInstanceOf(NotFoundException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.PROJECT_REPOSITORY_NOT_ACCESSIBLE);
        }

        @Test
        @DisplayName("이미 이 프로젝트에 있는 저장소는 409다")
        void rejectsAlreadyLinked() {
            given(installationService.findAccessibleRepository(USER_ID, "galpiii", "backend"))
                    .willReturn(Optional.of(githubRepository(1L, "galpiii/backend")));
            given(repositoryRepository
                    .existsByProjectIdAndGithubRepositoryIdAndUnlinkedAtIsNull(PROJECT_ID, 1L))
                    .willReturn(true);

            assertThatThrownBy(() -> service.resolve(USER_ID, PROJECT_ID,
                    "https://github.com/galpiii/backend"))
                    .isInstanceOf(ConflictException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.PROJECT_REPOSITORY_ALREADY_LINKED);
        }

        @Test
        @DisplayName("남의 프로젝트면 GitHub을 부르기 전에 끝난다")
        void rejectsForeignProject() {
            given(projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(PROJECT_ID, USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolve(USER_ID, PROJECT_ID,
                    "https://github.com/galpiii/backend"))
                    .isInstanceOf(NotFoundException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);

            verify(installationService, never())
                    .findAccessibleRepository(anyLong(), any(), any());
        }
    }
}
