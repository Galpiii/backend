package com.github.galpiii.galpi.domain.analysis.service;

import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRun;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunTarget;
import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunRepository;
import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunTargetRepository;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 작업과 저장소별 행을 만드는 짧은 트랜잭션.
 *
 * <p>권한 재검증이 GitHub을 여러 번 부르기 때문에 그 전체를 트랜잭션으로 감싸지 않는다.
 * 검증이 끝난 뒤 확정된 값만 여기서 한 번에 쓴다.
 *
 * <p>엔티티가 아니라 id를 받는 이유도 같다. 바깥에서 읽은 엔티티는 이 트랜잭션에서 준영속
 * 상태라, 그대로 상태를 바꿔도 반영되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisRunCreator {

    private final ProjectRepository projectRepository;
    private final GithubRepositoryRepository repositoryRepository;
    private final AnalysisRunRepository runRepository;
    private final AnalysisRunTargetRepository targetRepository;

    /**
     * @param targets              이번 작업이 처리할 저장소와 그 installation
     * @param installationSnapshot {@code githubRepositoryId -> installationId}. 워커가 이것만 본다
     */
    @Transactional
    public Long create(Long userId, Long projectId, List<TargetSpec> targets,
                       Map<Long, Long> installationSnapshot) {
        Project project = projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROJECT_NOT_FOUND));

        AnalysisRun run = runRepository.save(
                AnalysisRun.queue(project, project.getOwner(), installationSnapshot));

        Map<Long, GithubRepository> repositories = repositoryRepository.findAllById(
                        targets.stream().map(TargetSpec::repositoryId).toList()).stream()
                .collect(Collectors.toMap(GithubRepository::getId, Function.identity()));

        List<AnalysisRunTarget> rows = targets.stream()
                .map(target -> {
                    GithubRepository repository = repositories.get(target.repositoryId());
                    if (repository == null
                            || !repository.getProject().getId().equals(projectId)) {
                        throw new NotFoundException(ErrorCode.PROJECT_REPOSITORY_NOT_FOUND);
                    }
                    // 권한 재검증에서 받은 최신 이름·기본 브랜치·installation을 작업 생성과 함께
                    // 반영한다. 이름이 바뀐 저장소를 과거 owner/name으로 호출하지 않게 한다.
                    repository.refresh(target.snapshot());
                    return AnalysisRunTarget.pending(
                            run, repository, target.snapshot().installationId());
                })
                .toList();
        targetRepository.saveAll(rows);

        // 목록 화면이 프로젝트마다 최근 분석을 다시 조회하지 않도록 여기서 포인터를 옮긴다.
        project.markLastAnalysisRun(run.getId());

        log.info("[분석] 작업을 만들었다 runId={} projectId={} repositories={}",
                run.getId(), projectId, rows.size());
        return run.getId();
    }

    /**
     * 접근 권한을 잃은 저장소를 표시한다.
     *
     * <p>조직을 떠난 사용자가 과거 연결 저장소를 계속 분석하는 것을 막는 표시다. 다시 보이게
     * 되면 수집 경로에서 ACCESSIBLE로 되돌아간다.
     */
    @Transactional
    public void markInaccessible(List<Long> repositoryIds) {
        repositoryRepository.findAllById(repositoryIds)
                .forEach(GithubRepository::markInaccessible);
    }

    public record TargetSpec(Long repositoryId, RepositorySnapshot snapshot) {
    }
}
