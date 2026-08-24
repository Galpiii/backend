package com.github.galpiii.galpi.domain.analysis.service;

import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRun;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunStatus;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunTarget;
import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunRepository;
import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunTargetRepository;
import com.github.galpiii.galpi.domain.collection.RepositoryCollector.RepositoryCollectionResult;
import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.global.util.LogSafe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 워커가 진행 상황을 남기는 짧은 트랜잭션들.
 *
 * <p>실행 자체는 트랜잭션 밖에서 돈다. 저장소 하나를 수집하는 데 몇 분이 걸리고 그 시간의
 * 대부분이 GitHub 응답 대기라, 전체를 감싸면 DB 커넥션이 그만큼 묶인다. 상태 변화마다
 * 여기로 들어와 짧게 커밋한다.
 *
 * <p>덕분에 워커가 중간에 죽어도 그때까지의 저장소별 결과가 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisRunWriter {

    private final AnalysisRunRepository runRepository;
    private final AnalysisRunTargetRepository targetRepository;
    private final GithubRepositoryRepository repositoryRepository;

    @Transactional
    public void startTarget(Long targetId) {
        targetRepository.findById(targetId).ifPresent(AnalysisRunTarget::startCollecting);
    }

    @Transactional
    public void completeTarget(Long targetId, RepositoryCollectionResult result) {
        targetRepository.findById(targetId).ifPresent(target -> target.complete(
                result.commitSha(),
                result.collectedFileCount(),
                result.collectedBytes(),
                result.excludedFileCount(),
                result.prCollectedCount(),
                result.incompleteReasons()));
    }

    /**
     * 저장소 하나만 실패로 끝낸다.
     *
     * <p>{@code errorMessage}에 예외 메시지를 그대로 넣지 않는다. GitHub 응답 본문이나 URL이
     * 섞이면 토큰이 DB에 남을 수 있어, 마스킹을 거친 값만 저장한다.
     */
    @Transactional
    public void failTarget(Long targetId, String errorCode, String errorMessage) {
        targetRepository.findById(targetId)
                .ifPresent(target -> target.fail(errorCode, LogSafe.text(errorMessage)));
    }

    @Transactional
    public void skipTarget(Long targetId, List<IncompleteReason> reasons) {
        targetRepository.findById(targetId).ifPresent(target -> target.skip(reasons));
    }

    @Transactional
    public void finishRun(Long runId, AnalysisRunStatus status) {
        runRepository.findById(runId).ifPresent(run -> run.finish(status));
    }

    @Transactional
    public void failRun(Long runId, String errorCode, String errorMessage) {
        runRepository.findById(runId)
                .ifPresent(run -> run.fail(errorCode, LogSafe.text(errorMessage)));
    }

    /**
     * rate limit으로 중단한다. 재개 시각은 표시용이고, 서버가 그 시각에 스스로 깨어나지 않는다.
     */
    @Transactional
    public void rateLimitRun(Long runId, OffsetDateTime resumeAt) {
        runRepository.findById(runId).ifPresent(run -> run.markRateLimited(resumeAt));
    }

    /** 수집하며 확인한 저장소 정보를 스냅샷 컬럼에 반영한다. 이름이 바뀌었을 수 있다. */
    @Transactional
    public void refreshRepositorySnapshot(Long repositoryId, RepositorySnapshot snapshot) {
        repositoryRepository.findById(repositoryId)
                .ifPresent(repository -> repository.refresh(snapshot));
    }

    /** 접근이 막힌 저장소를 표시한다. 다시 보이면 수집 경로에서 ACCESSIBLE로 되돌아간다. */
    @Transactional
    public void markRepositoryInaccessible(Long repositoryId) {
        repositoryRepository.findById(repositoryId).ifPresent(GithubRepository::markInaccessible);
    }

    @Transactional(readOnly = true)
    public AnalysisRun requireRun(Long runId) {
        return runRepository.findById(runId).orElseThrow();
    }

    /** 작업을 요청한 사용자. 인계 직전 동의 확인이 이 값을 쓴다. */
    @Transactional(readOnly = true)
    public Long requesterIdOf(Long runId) {
        return runRepository.findRequesterId(runId).orElseThrow();
    }

    /**
     * 이 작업을 계속할 이유가 남아 있는지.
     *
     * <p>프로젝트가 삭제되면 진행 중이던 작업은 {@code CANCELLED}가 되지만, 이미 선점해 돌고
     * 있는 워커까지 그 갱신으로 멈추지는 않는다. 저장소 하나가 몇 분씩 걸리므로 저장소 사이
     * 체크포인트에서 이것을 보고 남은 저장소를 시작하지 않는다.
     */
    @Transactional(readOnly = true)
    public boolean isAbandoned(Long runId) {
        return runRepository.isAbandoned(runId);
    }
}
