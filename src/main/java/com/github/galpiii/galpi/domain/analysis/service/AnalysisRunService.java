package com.github.galpiii.galpi.domain.analysis.service;

import com.github.galpiii.galpi.domain.analysis.dto.AnalysisRunCreatedResponse;
import com.github.galpiii.galpi.domain.analysis.dto.AnalysisRunStatusResponse;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRun;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunStatus;
import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunRepository;
import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunTargetRepository;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.github.service.GithubInstallationService;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 분석 작업 생성과 조회.
 *
 * <p>작업을 만드는 이 순간이 <b>사용자 세션이 있는 유일한 시점</b>이다. 여기서 프로젝트의
 * 모든 저장소에 대해 현재 사용자의 접근 권한을 GitHub에 다시 물어 확인한다. 이 검증이
 * 조직을 떠난 사용자가 과거에 연결해 둔 저장소를 계속 분석하는 것을 막는다.
 *
 * <p>검증 결과로 만든 {@code githubRepositoryId -> installationId} 매핑을
 * {@code installation_snapshot}에 고정하면, 워커는 user access token 없이 동작할 수 있다.
 *
 * <p>메서드 전체에 트랜잭션을 걸지 않는다. 권한 재검증이 installation 수만큼 GitHub을
 * 호출하므로, 감싸면 외부 응답을 기다리는 내내 DB 커넥션이 묶인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisRunService {

    /** 아직 끝나지 않은 상태. 같은 프로젝트에 두 개가 동시에 있으면 서로의 결과를 덮는다. */
    private static final List<AnalysisRunStatus> IN_FLIGHT =
            List.of(AnalysisRunStatus.QUEUED, AnalysisRunStatus.RUNNING);

    private final ProjectRepository projectRepository;
    private final GithubRepositoryRepository repositoryRepository;
    private final GithubInstallationService installationService;
    private final AnalysisRunRepository runRepository;
    private final AnalysisRunTargetRepository targetRepository;
    private final AnalysisRunCreator creator;

    public AnalysisRunCreatedResponse create(Long userId, Long projectId) {
        // GitHub을 부르기 전에 소유권부터 본다. 남의 프로젝트면 외부 호출 없이 끝난다.
        requireOwnedProject(userId, projectId);

        if (runRepository.existsByProjectIdAndStatusIn(projectId, IN_FLIGHT)) {
            throw new ConflictException(ErrorCode.ANALYSIS_ALREADY_RUNNING);
        }

        List<GithubRepository> linked = repositoryRepository.findAllByProjectId(projectId);
        if (linked.isEmpty()) {
            throw new BadRequestException(ErrorCode.ANALYSIS_NO_REPOSITORY);
        }

        Map<Long, RepositorySnapshot> accessible = installationService.accessibleSnapshots(
                userId, linked.stream().map(GithubRepository::getGithubRepositoryId).toList());

        List<AnalysisRunCreator.TargetSpec> targets = new ArrayList<>();
        List<Long> inaccessibleIds = new ArrayList<>();
        Map<Long, Long> installationSnapshot = new LinkedHashMap<>();

        for (GithubRepository repository : linked) {
            RepositorySnapshot snapshot = accessible.get(repository.getGithubRepositoryId());
            if (snapshot == null) {
                inaccessibleIds.add(repository.getId());
                continue;
            }
            targets.add(new AnalysisRunCreator.TargetSpec(
                    repository.getId(), snapshot.installationId()));
            installationSnapshot.put(repository.getGithubRepositoryId(),
                    snapshot.installationId());
        }

        if (!inaccessibleIds.isEmpty()) {
            log.warn("[분석] 접근할 수 없는 저장소를 표시한다 userId={} projectId={} count={}",
                    userId, projectId, inaccessibleIds.size());
            creator.markInaccessible(inaccessibleIds);
        }
        if (targets.isEmpty()) {
            throw new ForbiddenException(ErrorCode.ANALYSIS_NO_ACCESSIBLE_REPOSITORY);
        }

        Long runId = creator.create(userId, projectId, targets, installationSnapshot);
        return new AnalysisRunCreatedResponse(runId, AnalysisRunStatus.QUEUED, targets.size(),
                inaccessibleIds.size());
    }

    /** 폴링용 조회. 저장소별 상태까지 함께 내려 어디가 왜 비었는지 화면이 보여줄 수 있게 한다. */
    @Transactional(readOnly = true)
    public AnalysisRunStatusResponse get(Long userId, Long analysisRunId) {
        AnalysisRun run = runRepository.findById(analysisRunId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ANALYSIS_RUN_NOT_FOUND));

        // 남의 작업은 존재 자체를 알려주지 않는다.
        if (!run.getProject().getUser().getId().equals(userId)) {
            throw new NotFoundException(ErrorCode.ANALYSIS_RUN_NOT_FOUND);
        }
        return AnalysisRunStatusResponse.of(run,
                targetRepository.findAllWithRepositoryByAnalysisRunId(analysisRunId));
    }

    private void requireOwnedProject(Long userId, Long projectId) {
        projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROJECT_NOT_FOUND));
    }
}
