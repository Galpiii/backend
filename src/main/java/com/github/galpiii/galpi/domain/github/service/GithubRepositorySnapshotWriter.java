package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 목록을 새로 읽어 온 김에 연결된 저장소의 스냅샷을 현재 값으로 맞춘다.
 *
 * <p>별도 빈으로 둔 이유는 트랜잭션 범위 때문이다. 저장소 목록 조회는 installation 수만큼
 * GitHub을 호출하는데, 그 전체를 트랜잭션으로 감싸면 외부 응답을 기다리는 동안 DB 커넥션을
 * 붙잡고 있게 된다. 조회는 트랜잭션 밖에서 끝내고 쓰기만 여기서 짧게 처리한다.
 */
@Component
@RequiredArgsConstructor
public class GithubRepositorySnapshotWriter {

    private final GithubRepositoryRepository repositoryRepository;

    /**
     * @param current GitHub에서 방금 읽은 {@code githubRepositoryId → 현재 모습}
     * @return 이 프로젝트에 연결되어 있는 {@code githubRepositoryId}
     */
    @Transactional
    public Set<Long> refreshLinked(Long projectId, Map<Long, RepositorySnapshot> current) {
        Set<Long> linkedIds = new HashSet<>();

        for (GithubRepository repository : repositoryRepository.findAllByProjectId(projectId)) {
            Long githubId = repository.getGithubRepositoryId();
            linkedIds.add(githubId);

            RepositorySnapshot snapshot = current.get(githubId);
            if (snapshot != null) {
                repository.refresh(snapshot);
            }
        }
        return linkedIds;
    }
}
