package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.client.dto.GithubInstallationResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubRepositoryResponse;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.dto.InstallationRepositoriesResponse;
import com.github.galpiii.galpi.domain.github.dto.InstallationSummaryResponse;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.dto.SelectableRepositoryResponse;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 사용자가 접근할 수 있는 installation과 저장소를 GitHub에 직접 묻는다.
 *
 * <p>{@code GET /user/installations}와 {@code GET /user/installations/{id}/repositories}는
 * 이미 "설치 범위 ∩ 사용자 접근 권한"이 적용된 결과다. 조직 멤버십을 따로 조회해 교집합을
 * 계산하지 마라 — 두 번 계산하면 GitHub 쪽 규칙이 바뀔 때마다 갈피만 틀린다.
 *
 * <p>이 목록은 DB에 저장하지 않는다. {@code repositories} 테이블에는 프로젝트에 실제로 연결된
 * 것만 들어간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubInstallationService {

    private static final int VERIFY_ATTEMPTS = 3;
    private static final long VERIFY_RETRY_MILLIS = 1_000L;

    private final GithubApiClient apiClient;
    private final GithubUserTokenService userTokenService;
    private final GithubRepositorySnapshotWriter snapshotWriter;
    private final ProjectRepository projectRepository;
    private final GithubAppProperties properties;

    public List<InstallationSummaryResponse> listInstallations(Long userId) {
        String token = userTokenService.require(userId);
        return apiClient.getUserInstallations(token).stream()
                .map(installation -> InstallationSummaryResponse.from(installation, properties))
                .toList();
    }

    /**
     * installation별로 묶은 저장소 목록.
     *
     * <p>{@code projectId}가 오면 이미 연결된 저장소에 표시를 달고, 겸사겸사 스냅샷도 맞춘다.
     * GitHub에서 저장소 이름이나 소유자가 바뀌어도 연결은 {@code github_repository_id}로
     * 유지되지만, 화면에 옛 이름이 계속 남으면 사용자는 연결이 깨진 것으로 읽는다.
     *
     * <p>GitHub 호출을 모두 끝낸 뒤에 쓰기를 한 번에 처리한다. 트랜잭션으로 전체를 감싸면
     * installation 수만큼의 외부 응답을 기다리는 동안 DB 커넥션이 묶인다.
     */
    public List<InstallationRepositoriesResponse> listRepositories(Long userId, Long projectId) {
        String token = userTokenService.require(userId);
        Long ownedProjectId = ownedProjectId(userId, projectId);

        Map<Long, RepositorySnapshot> current = new LinkedHashMap<>();
        Map<Long, List<GithubRepositoryResponse>> byInstallation = new LinkedHashMap<>();
        List<GithubInstallationResponse> installations = apiClient.getUserInstallations(token);

        for (GithubInstallationResponse installation : installations) {
            List<GithubRepositoryResponse> repositories =
                    apiClient.getInstallationRepositories(token, installation.id());
            byInstallation.put(installation.id(), repositories);

            for (GithubRepositoryResponse repository : repositories) {
                current.putIfAbsent(repository.id(), toSnapshot(repository, installation.id()));
            }
        }

        Set<Long> linkedIds = ownedProjectId == null
                ? Set.of()
                : snapshotWriter.refreshLinked(ownedProjectId, current);

        List<InstallationRepositoriesResponse> grouped = new ArrayList<>();
        for (GithubInstallationResponse installation : installations) {
            List<SelectableRepositoryResponse> items =
                    byInstallation.getOrDefault(installation.id(), List.of()).stream()
                            .map(repository -> SelectableRepositoryResponse.of(
                                    repository, linkedIds.contains(repository.id())))
                            .toList();

            grouped.add(new InstallationRepositoriesResponse(
                    InstallationSummaryResponse.from(installation, properties), items));
        }
        return grouped;
    }

    /**
     * 지금 이 사용자가 실제로 접근할 수 있는 저장소를 {@code githubRepositoryId} 기준으로 모은다.
     * 프론트가 보낸 id를 저장 전에 대조하는 데 쓴다.
     *
     * <p>목록 조회와 달리 잘린 결과를 받지 않는다. 이 맵에 없는 id는 "권한 없음"으로 거부되므로,
     * 페이지네이션 상한에 걸린 목록으로 판정하면 정당한 저장소가 403이 된다.
     */
    public Map<Long, RepositorySnapshot> accessibleSnapshots(Long userId) {
        String token = userTokenService.require(userId);
        Map<Long, RepositorySnapshot> snapshots = new LinkedHashMap<>();

        for (GithubInstallationResponse installation : apiClient.getUserInstallationsComplete(token)) {
            for (GithubRepositoryResponse repository
                    : apiClient.getInstallationRepositoriesComplete(token, installation.id())) {
                snapshots.putIfAbsent(repository.id(),
                        toSnapshot(repository, installation.id()));
            }
        }
        return snapshots;
    }

    /**
     * 콜백으로 받은 installation_id가 정말 이 사용자 것인지 대조한다.
     *
     * <p>GitHub 공식 문서가 명시적으로 경고하는 지점이다 — setup URL은 누구나 위조한
     * installation_id를 달고 부를 수 있다. 사용자 토큰으로 조회한 목록에 없으면 남의 설치다.
     *
     * <p>설치 직후에는 목록 반영이 조금 늦을 수 있어 짧게 다시 본다. rate limit 대기와 달리
     * 초 단위 전파 지연이므로 요청 안에서 기다려도 된다.
     */
    public boolean ownsInstallation(Long userId, Long installationId) {
        String token = userTokenService.require(userId);

        for (int attempt = 0; attempt < VERIFY_ATTEMPTS; attempt++) {
            if (attempt > 0 && !sleepBeforeRetry()) {
                return false;
            }
            boolean found = apiClient.getUserInstallations(token).stream()
                    .anyMatch(installation -> installationId.equals(installation.id()));
            if (found) {
                return true;
            }
        }

        log.warn("[GitHub] 콜백의 installation_id가 사용자 설치 목록에 없다 userId={}", userId);
        return false;
    }

    private boolean sleepBeforeRetry() {
        try {
            Thread.sleep(VERIFY_RETRY_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** GitHub을 부르기 전에 프로젝트 소유권부터 확인한다. 남의 프로젝트면 여기서 끝난다. */
    private Long ownedProjectId(Long userId, Long projectId) {
        if (projectId == null) {
            return null;
        }
        projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROJECT_NOT_FOUND));
        return projectId;
    }

    private static RepositorySnapshot toSnapshot(GithubRepositoryResponse repository,
                                                 Long installationId) {
        return new RepositorySnapshot(
                repository.id(),
                installationId,
                repository.ownerLogin(),
                repository.name(),
                repository.fullName(),
                Boolean.TRUE.equals(repository.isPrivate()),
                repository.defaultBranch(),
                repository.htmlUrl());
    }
}
