package com.github.galpiii.galpi.domain.project.service;

import com.github.galpiii.galpi.domain.github.client.dto.GithubRepositoryResponse;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.dto.SelectableRepositoryResponse;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.exception.GithubReauthRequiredException;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.github.service.GithubInstallationService;
import com.github.galpiii.galpi.domain.github.service.GithubUserTokenService;
import com.github.galpiii.galpi.domain.github.support.GithubRepositoryUrlParser;
import com.github.galpiii.galpi.domain.github.support.GithubRepositoryUrlParser.RepositoryUrl;
import com.github.galpiii.galpi.domain.project.dto.LinkedRepositoryResponse;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.ForbiddenException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 프로젝트와 GitHub 저장소의 연결.
 *
 * <p>한 프로젝트에 서로 다른 installation의 저장소가 섞일 수 있다. 개인 계정 저장소와 조직 A·B의
 * 저장소를 한 프로젝트로 묶는 것이 기본 사용 방식이므로, installation 단위로 묶어 제약하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectRepositoryService {

    private final ProjectRepository projectRepository;
    private final GithubRepositoryRepository repositoryRepository;
    private final GithubInstallationService installationService;
    private final ProjectRepositoryLinkWriter linkWriter;
    private final GithubRepositoryUrlParser urlParser;
    private final GithubUserTokenService userTokenService;

    @Transactional(readOnly = true)
    public List<LinkedRepositoryResponse> list(Long userId, Long projectId) {
        Project project = ownedProject(userId, projectId);
        return repositoryRepository.findAllByProjectId(project.getId()).stream()
                .map(LinkedRepositoryResponse::from)
                .toList();
    }

    /**
     * 저장소를 프로젝트에 연결한다.
     *
     * <p>프론트가 보낸 {@code githubRepositoryId}는 믿지 않는다. 지금 이 사용자가 실제로
     * 접근 가능한 목록을 GitHub에서 새로 받아 대조하고, 없는 id가 하나라도 있으면 거부한다.
     * 저장하는 값도 요청 본문이 아니라 그 조회 결과에서 가져온다.
     *
     * <p>멱등하다. 이미 연결된 저장소가 섞여 있으면 건너뛰고 나머지만 붙인 뒤, 요청한 저장소
     * 전부의 현재 모습을 돌려준다. 프론트가 화면의 선택 상태를 통째로 보내도 되고, 같은 요청을
     * 두 번 보내도 결과가 같다.
     *
     * <p>이 메서드에는 트랜잭션을 걸지 않는다. 권한 재검증이 installation 수만큼 GitHub을
     * 호출하므로, 전체를 감싸면 외부 응답을 기다리는 내내 DB 커넥션이 묶인다. 쓰기는
     * {@link ProjectRepositoryLinkWriter}가 짧게 처리한다.
     */
    public List<LinkedRepositoryResponse> link(Long userId, Long projectId,
                                               List<Long> githubRepositoryIds) {
        // GitHub을 부르기 전에 소유권부터 본다. 남의 프로젝트면 외부 호출 없이 여기서 끝난다.
        ownedProject(userId, projectId);
        Set<Long> requested = new LinkedHashSet<>(githubRepositoryIds);

        Map<Long, RepositorySnapshot> accessible =
                installationService.accessibleSnapshots(userId, requested);
        List<Long> denied = requested.stream()
                .filter(id -> !accessible.containsKey(id))
                .toList();
        if (!denied.isEmpty()) {
            log.warn("[GitHub] 접근 권한이 없는 저장소 연결 시도 userId={} projectId={} count={}",
                    userId, projectId, denied.size());
            throw new ForbiddenException(ErrorCode.GITHUB_REPOSITORY_ACCESS_DENIED);
        }

        return linkWriter.link(userId, projectId, requested, accessible).stream()
                .map(LinkedRepositoryResponse::from)
                .toList();
    }

    /**
     * URL로 저장소 하나를 찾는다. 목록에 안 뜨는 저장소를 사용자가 직접 넣는 경로다.
     *
     * <p>여기서는 확인만 하고 저장하지 않는다. 확인된 저장소는 프론트의 선택 목록에 더해지고,
     * 실제 연결은 {@link #link}가 같은 권한 재검증을 다시 거쳐 처리한다.
     *
     * <p><b>없는 저장소와 접근 권한이 없는 저장소를 구분해 알려주지 않는다.</b> 둘 다 같은
     * 404다 — 구분하면 URL만 바꿔 넣어 보는 것으로 남의 비공개 저장소가 존재하는지 알아낼 수
     * 있다. 이미 이 프로젝트에 있는 저장소만 따로 409로 구분한다. 다른 프로젝트에 이미
     * 있는 것은 중복이 아니다. 프로젝트 간에는 같은 저장소를 공유할 수 있다.
     */
    public SelectableRepositoryResponse resolve(Long userId, Long projectId, String url) {
        // GitHub을 부르기 전에 소유권부터 본다.
        ownedProject(userId, projectId);

        RepositoryUrl parsed = urlParser.parse(url).orElseThrow(() -> {
            log.info("[GitHub] 저장소 URL 형식이 아니다 userId={} projectId={}", userId, projectId);
            return new BadRequestException(ErrorCode.PROJECT_REPOSITORY_URL_INVALID);
        });

        GithubRepositoryResponse repository = installationService
                .findAccessibleRepository(userId, parsed.owner(), parsed.name())
                .orElseThrow(() -> {
                    log.info("[GitHub] URL로 찾은 저장소에 접근할 수 없다 userId={} projectId={}",
                            userId, projectId);
                    return new NotFoundException(ErrorCode.PROJECT_REPOSITORY_NOT_ACCESSIBLE);
                });

        boolean alreadyLinked = repositoryRepository
                .existsByProjectIdAndGithubRepositoryIdAndUnlinkedAtIsNull(
                        projectId, repository.id());
        if (alreadyLinked) {
            throw new ConflictException(ErrorCode.PROJECT_REPOSITORY_ALREADY_LINKED);
        }
        return SelectableRepositoryResponse.of(repository, false);
    }

    /**
     * 연결을 끊는다.
     *
     * <p>소프트 삭제다. {@code analysis_run_repositories}와 {@code pull_requests}가 이 행을
     * {@code ON DELETE CASCADE}로 물고 있어서, 물리 삭제하면 저장소 하나를 빼는 것만으로 그
     * 저장소의 수집 근거와 과거 분석 결과가 함께 사라진다. 프로젝트 삭제조차 아무것도 지우지
     * 않는데 그보다 작은 이 행동이 이력을 지우는 것은 앞뒤가 맞지 않는다.
     *
     * <p>같은 저장소를 다시 연결하면 이 행이 되살아나므로 끊기 전 이력이 그대로 이어진다.
     *
     * <p>이미 끊긴 저장소를 다시 끊으면 404다. 조회가 살아 있는 것만 보기 때문인데, 그게 맞다 —
     * 목록에 없는 저장소를 끊으라는 요청은 화면이 낡았다는 뜻이고, 404가 그것을 알려 준다.
     *
     * <p>GitHub 연결이 끊긴 상태에서는 거부한다. 이 경로는 GitHub을 부르지 않아 토큰 없이도
     * 동작하지만, 저장소 구성을 바꾸는 것은 연결이 살아 있을 때만 할 수 있는 일이다 —
     * 연결이 끊긴 동안 허용되는 것은 조회뿐이다.
     */
    @Transactional
    public void unlink(Long userId, Long projectId, Long repositoryId) {
        if (!userTokenService.isValid(userId)) {
            log.info("[GitHub] 연결이 끊긴 상태에서 저장소 삭제를 시도 userId={} projectId={}",
                    userId, projectId);
            throw new GithubReauthRequiredException();
        }
        Project project = ownedProject(userId, projectId);
        GithubRepository repository = repositoryRepository
                .findByIdAndProjectId(repositoryId, project.getId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROJECT_REPOSITORY_NOT_FOUND));

        repository.unlink();
        log.info("[GitHub] 저장소 연결을 끊었다 projectId={} repositoryId={}", projectId, repositoryId);
    }

    private Project ownedProject(Long userId, Long projectId) {
        return projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROJECT_NOT_FOUND));
    }
}
