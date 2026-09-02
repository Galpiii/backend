package com.github.galpiii.galpi.domain.pullrequest.service;

import com.github.galpiii.galpi.domain.github.event.GithubDisconnectedEvent;
import com.github.galpiii.galpi.domain.project.event.ProjectDeletedEvent;
import com.github.galpiii.galpi.domain.project.event.ProjectRepositoryUnlinkedEvent;
import com.github.galpiii.galpi.domain.pullrequest.entity.SummaryFailureCode;
import com.github.galpiii.galpi.domain.pullrequest.repository.PullRequestAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/** 실행 근거가 사라지면 대기·실행 중인 PR 요약을 같은 트랜잭션에서 취소한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PullRequestAnalysisScopeCanceller {

    private final PullRequestAnalysisRepository analysisRepository;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onProjectDeleted(ProjectDeletedEvent event) {
        logCancelled("project", event.projectId(), analysisRepository.cancelInFlightByProject(
                event.projectId(), SummaryFailureCode.PROJECT_DELETED, OffsetDateTime.now()));
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onRepositoryUnlinked(ProjectRepositoryUnlinkedEvent event) {
        logCancelled("repository", event.repositoryId(),
                analysisRepository.cancelInFlightByRepository(event.repositoryId(),
                        SummaryFailureCode.REPOSITORY_UNLINKED, OffsetDateTime.now()));
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onGithubDisconnected(GithubDisconnectedEvent event) {
        logCancelled("user", event.userId(), analysisRepository.cancelInFlightByRequester(
                event.userId(), SummaryFailureCode.GITHUB_DISCONNECTED, OffsetDateTime.now()));
    }

    private void logCancelled(String scope, Long id, int count) {
        if (count > 0) {
            log.info("[요약] 실행 근거 해제로 작업을 취소 scope={} id={} count={}",
                    scope, id, count);
        }
    }
}
