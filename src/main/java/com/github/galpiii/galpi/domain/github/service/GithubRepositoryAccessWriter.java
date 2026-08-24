package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.dto.RepositoryAccessResyncResponse;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 접근 확인 결과를 반영하는 짧은 트랜잭션.
 *
 * <p>엔티티가 아니라 id를 받는다. GitHub 호출 바깥에서 읽은 엔티티는 이 트랜잭션에서 준영속
 * 상태라 그대로 상태를 바꿔도 반영되지 않는다. 응답 DTO도 여기서 만든다 — 트랜잭션 밖으로
 * 엔티티를 넘기면 지연 로딩된 프로젝트를 읽는 순간 터진다.
 */
@Component
@RequiredArgsConstructor
class GithubRepositoryAccessWriter {

    private final GithubRepositoryRepository repositoryRepository;

    @Transactional
    List<RepositoryAccessResyncResponse.RepositoryAccess> apply(
            List<Long> repositoryIds, Map<Long, RepositorySnapshot> accessible) {
        List<GithubRepository> repositories = repositoryRepository.findAllById(repositoryIds);
        return repositories.stream()
                .map(repository -> {
                    RepositorySnapshot snapshot =
                            accessible.get(repository.getGithubRepositoryId());
                    if (snapshot == null) {
                        // 지금 보이지 않는다고 연결을 끊지 않는다. 조직 승인이 늦어지는 것처럼
                        // 되돌아올 수 있는 상태이고, 지우면 사용자는 사라진 이유를 알 수 없다.
                        repository.markInaccessible();
                    } else {
                        repository.refresh(snapshot);
                    }
                    return RepositoryAccessResyncResponse.RepositoryAccess.from(repository);
                })
                .toList();
    }
}
