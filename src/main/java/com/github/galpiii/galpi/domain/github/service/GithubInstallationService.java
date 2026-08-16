package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.client.GithubRequestBudget;
import com.github.galpiii.galpi.domain.github.client.dto.GithubInstallationResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubRepositoryResponse;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.dto.InstallationRepositoriesResponse;
import com.github.galpiii.galpi.domain.github.dto.InstallationSummaryResponse;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.dto.SelectableRepositoryResponse;
import com.github.galpiii.galpi.domain.github.exception.GithubInstallationUnavailableException;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
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

    private final GithubApiClient apiClient;
    private final GithubUserTokenService userTokenService;
    private final GithubRepositoryRepository repositoryRepository;
    private final ProjectRepository projectRepository;
    private final GithubAppProperties properties;
    private final GithubUserOperationLimiter operationLimiter;

    public List<InstallationSummaryResponse> listInstallations(Long userId) {
        return operationLimiter.execute(userId, () -> listInstallationsWithinLimit(userId));
    }

    private List<InstallationSummaryResponse> listInstallationsWithinLimit(Long userId) {
        String token = userTokenService.require(userId);
        GithubRequestBudget budget = apiClient.newOperationBudget();
        return apiClient.getUserInstallations(token, budget).stream()
                .map(installation -> InstallationSummaryResponse.from(installation, properties))
                .toList();
    }

    /**
     * installation별로 묶은 저장소 목록.
     *
     * <p>{@code projectId}가 오면 이미 연결된 저장소에 표시만 단다. GET 목록 조회는 DB 상태를
     * 변경하지 않으며, 저장소 스냅샷은 실제 연결 명령에서 갱신한다.
     */
    public List<InstallationRepositoriesResponse> listRepositories(Long userId, Long projectId) {
        return operationLimiter.execute(
                userId, () -> listRepositoriesWithinLimit(userId, projectId));
    }

    private List<InstallationRepositoriesResponse> listRepositoriesWithinLimit(
            Long userId, Long projectId) {
        String token = userTokenService.require(userId);
        Long ownedProjectId = ownedProjectId(userId, projectId);
        GithubRequestBudget budget = apiClient.newOperationBudget();

        Map<Long, List<GithubRepositoryResponse>> byInstallation = new LinkedHashMap<>();
        List<GithubInstallationResponse> installations = apiClient.getUserInstallations(token, budget);

        for (GithubInstallationResponse installation : installations) {
            if (budget.isRequestLimitReached()) {
                break;
            }
            if (installation.isSuspended()) {
                byInstallation.put(installation.id(), List.of());
                continue;
            }

            List<GithubRepositoryResponse> repositories;
            try {
                repositories = apiClient.getInstallationRepositories(
                        token, installation.id(), budget);
            } catch (GithubInstallationUnavailableException e) {
                // 목록과 상세 조회 사이에 제거된 installation 하나 때문에 나머지를 숨기지 않는다.
                log.info("[GitHub] 목록 조회 중 사라진 installation을 건너뛴다 installationId={}",
                        installation.id());
                continue;
            }
            byInstallation.put(installation.id(), repositories);
        }

        Set<Long> linkedIds = ownedProjectId == null
                ? Set.of()
                : new HashSet<>(repositoryRepository
                        .findGithubRepositoryIdsByProjectId(ownedProjectId));

        List<InstallationRepositoriesResponse> grouped = new ArrayList<>();
        for (GithubInstallationResponse installation : installations) {
            if (!installation.isSuspended() && !byInstallation.containsKey(installation.id())) {
                continue;
            }
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
     * 요청한 저장소가 지금 이 사용자에게 실제로 보이는지 GitHub에 물어 확인한다.
     * 프론트가 보낸 id를 저장 전에 대조하는 데 쓴다.
     *
     * <p>목록 조회와 달리 잘린 결과를 받지 않는다. 이 맵에 없는 id는 "권한 없음"으로 거부되므로,
     * 페이지네이션 상한에 걸린 목록으로 판정하면 정당한 저장소가 403이 된다.
     *
     * <p>요청한 id만 남기고 다 찾으면 즉시 멈춘다. 존재하지 않는 id가 섞이면 installation을
     * 끝까지 훑을 수 있으므로, 서비스 진입점의 사용자별 동시 실행 제한과 이 작업 전체가 공유하는
     * API 호출 budget·deadline이 최종 상한을 보장한다.
     */
    public Map<Long, RepositorySnapshot> accessibleSnapshots(Long userId, Collection<Long> requestedIds) {
        if (requestedIds.isEmpty()) {
            return Map.of();
        }

        return operationLimiter.execute(
                userId, () -> accessibleSnapshotsWithinLimit(userId, requestedIds));
    }

    private Map<Long, RepositorySnapshot> accessibleSnapshotsWithinLimit(
            Long userId, Collection<Long> requestedIds) {
        String token = userTokenService.require(userId);
        GithubRequestBudget budget = apiClient.newOperationBudget();
        Set<Long> remaining = new HashSet<>(requestedIds);
        Map<Long, RepositorySnapshot> snapshots = new LinkedHashMap<>();
        boolean lookupIncomplete = false;

        for (GithubInstallationResponse installation
                : apiClient.getUserInstallationsComplete(token, budget)) {
            if (installation.isSuspended()) {
                continue;
            }

            List<GithubRepositoryResponse> repositories;
            try {
                repositories = apiClient.getInstallationRepositoriesComplete(
                        token, installation.id(), budget);
            } catch (GithubInstallationUnavailableException e) {
                // 다른 installation에서 같은 저장소를 찾을 수도 있어 즉시 실패하지 않는다.
                // 다 찾지 못한 채 끝나면 이 누락을 403 근거로 쓰지 않고 재시도 가능한 오류로 낸다.
                lookupIncomplete = true;
                log.info("[GitHub] 권한 대조 중 사라진 installation을 건너뛴다 installationId={}",
                        installation.id());
                continue;
            }

            for (GithubRepositoryResponse repository : repositories) {
                // 처음 본 id일 때만 remove가 true다. 뒤 installation의 같은 저장소는 덮지 않는다.
                if (remaining.remove(repository.id())) {
                    snapshots.put(repository.id(), toSnapshot(repository, installation.id()));
                }
            }
            if (remaining.isEmpty()) {
                break;
            }
        }
        if (!remaining.isEmpty() && lookupIncomplete) {
            throw new GithubInstallationUnavailableException();
        }
        return snapshots;
    }

    /**
     * 콜백으로 받은 installation_id가 정말 이 사용자 것인지 대조한다.
     *
     * <p>GitHub 공식 문서가 명시적으로 경고하는 지점이다 — setup URL은 누구나 위조한
     * installation_id를 달고 부를 수 있다. 사용자 토큰으로 조회한 목록에 없으면 남의 설치다.
     *
     * <p>이것도 권한 판정이라 잘린 목록을 쓰지 않는다. 상한 뒤쪽에 있다는 이유로 정당한 설치가
     * 남의 것으로 판정되면 안 된다. 대신 조회 자체가 실패할 수 있으므로, 콜백은 그 예외를
     * "확인 안 됨"으로 받아 리다이렉트를 유지해야 한다.
     */
    public boolean ownsInstallation(Long userId, Long installationId) {
        return operationLimiter.execute(
                userId, () -> ownsInstallationWithinLimit(userId, installationId));
    }

    private boolean ownsInstallationWithinLimit(Long userId, Long installationId) {
        String token = userTokenService.require(userId);
        GithubRequestBudget budget = apiClient.newOperationBudget();

        boolean found = apiClient.getUserInstallationsComplete(token, budget).stream()
                .anyMatch(installation -> installationId.equals(installation.id()));
        if (found) {
            return true;
        }

        log.warn("[GitHub] 콜백의 installation_id가 사용자 설치 목록에 없다 userId={}", userId);
        return false;
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
