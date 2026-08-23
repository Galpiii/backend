package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.dto.RepositoryAccessResyncResponse;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.entity.RepositoryAccessStatus;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 연결해 둔 저장소의 접근 상태를 GitHub에 다시 물어 맞춘다.
 *
 * <p>재연결 직후에 쓴다. 연결을 끊어도 {@code repositories} 행은 남기 때문에 재연결하면
 * {@code github_repository_id} 기준으로 그대로 이어지지만, 그 사이에 조직에서 나갔거나 설치
 * 범위에서 빠진 저장소는 다시 보이지 않는다. 분석을 실행해야만 그 사실이 드러나면 사용자는
 * 결과가 비는 이유를 알 수 없다.
 *
 * <p>메서드에 트랜잭션을 걸지 않는다. 권한 확인이 installation 수만큼 GitHub을 호출하므로
 * 감싸면 외부 응답을 기다리는 내내 DB 커넥션이 묶인다. 쓰기는
 * {@link GithubRepositoryAccessWriter}가 짧게 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubRepositoryAccessService {

    private final GithubRepositoryRepository repositoryRepository;
    private final GithubInstallationService installationService;
    private final GithubRepositoryAccessWriter writer;

    public RepositoryAccessResyncResponse resync(Long userId) {
        List<GithubRepository> linked = repositoryRepository.findAllByProjectOwnerId(userId);
        if (linked.isEmpty()) {
            return new RepositoryAccessResyncResponse(0, 0, 0, List.of());
        }

        Map<Long, RepositorySnapshot> accessible = installationService.accessibleSnapshots(
                userId, linked.stream().map(GithubRepository::getGithubRepositoryId).toList());

        List<RepositoryAccessResyncResponse.RepositoryAccess> updated = writer.apply(
                linked.stream().map(GithubRepository::getId).toList(), accessible);

        int inaccessible = (int) updated.stream()
                .filter(repository ->
                        repository.accessStatus() == RepositoryAccessStatus.INACCESSIBLE)
                .count();
        if (inaccessible > 0) {
            log.info("[GitHub] 재연결 후에도 접근할 수 없는 저장소가 있다 userId={} count={}",
                    userId, inaccessible);
        }
        return new RepositoryAccessResyncResponse(
                updated.size(),
                updated.size() - inaccessible,
                inaccessible,
                updated);
    }
}
