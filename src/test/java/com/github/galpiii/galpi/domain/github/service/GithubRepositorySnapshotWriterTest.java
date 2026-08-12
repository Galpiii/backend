package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.entity.RepositoryAccessStatus;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("GithubRepositorySnapshotWriter — 연결된 저장소 스냅샷 갱신")
class GithubRepositorySnapshotWriterTest {

    private static final long PROJECT_ID = 3L;
    private static final long INSTALLATION_ID = 100L;

    @Mock
    private GithubRepositoryRepository repositoryRepository;

    private GithubRepositorySnapshotWriter writer;

    @BeforeEach
    void setUp() {
        writer = new GithubRepositorySnapshotWriter(repositoryRepository);
    }

    private static RepositorySnapshot snapshot(long id, String fullName) {
        String owner = fullName.substring(0, fullName.indexOf('/'));
        String name = fullName.substring(fullName.indexOf('/') + 1);
        return new RepositorySnapshot(id, INSTALLATION_ID, owner, name, fullName, true, "main",
                "https://github.com/" + fullName);
    }

    private static GithubRepository linked(long githubId, String fullName) {
        return GithubRepository.link(Project.create(mock(User.class), "갈피"),
                snapshot(githubId, fullName));
    }

    @Test
    @DisplayName("이름이 바뀌었으면 스냅샷을 현재 값으로 맞추고 연결 키는 그대로 둔다")
    void refreshesRenamedRepository() {
        GithubRepository repository = linked(1L, "wb/notes");
        given(repositoryRepository.findAllByProjectId(PROJECT_ID)).willReturn(List.of(repository));

        writer.refreshLinked(PROJECT_ID, Map.of(1L, snapshot(1L, "galpiii/journal")));

        assertThat(repository.getGithubRepositoryId()).isEqualTo(1L);
        assertThat(repository.getFullName()).isEqualTo("galpiii/journal");
        assertThat(repository.getOwner()).isEqualTo("galpiii");
        assertThat(repository.getName()).isEqualTo("journal");
        assertThat(repository.getLastSyncedAt()).isNotNull();
    }

    @Test
    @DisplayName("연결된 저장소의 github id를 돌려준다")
    void returnsLinkedIds() {
        given(repositoryRepository.findAllByProjectId(PROJECT_ID))
                .willReturn(List.of(linked(1L, "wb/notes"), linked(2L, "galpiii/backend")));

        assertThat(writer.refreshLinked(PROJECT_ID, Map.of())).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("이번 목록에 없는 저장소는 건드리지 않는다 — 권한 상실 판정은 여기서 하지 않는다")
    void leavesMissingRepositoryUntouched() {
        GithubRepository repository = linked(1L, "wb/notes");
        given(repositoryRepository.findAllByProjectId(PROJECT_ID)).willReturn(List.of(repository));

        writer.refreshLinked(PROJECT_ID, Map.of(999L, snapshot(999L, "other/repo")));

        assertThat(repository.getFullName()).isEqualTo("wb/notes");
        assertThat(repository.getAccessStatus()).isEqualTo(RepositoryAccessStatus.ACCESSIBLE);
    }
}
