package com.github.galpiii.galpi.domain.pullrequest.support;

import com.github.galpiii.galpi.domain.consent.AiDataNotice;
import com.github.galpiii.galpi.domain.consent.config.ConsentProperties;
import com.github.galpiii.galpi.domain.consent.exception.AiDataConsentRequiredException;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.domain.pullrequest.entity.SummaryFailureCode;
import com.github.galpiii.galpi.domain.pullrequest.repository.PullRequestAnalysisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** PR 내용이 외부 AI로 나가기 직전에 claim과 실행 근거를 현재 DB 상태로 다시 확인한다. */
@Component
@RequiredArgsConstructor
public class SummaryHandoffGuard {

    private final PullRequestAnalysisRepository analysisRepository;
    private final ConsentProperties consentProperties;

    @Transactional(readOnly = true)
    public void check(Long analysisId, String workerId) {
        String consentVersion = consentProperties.aiDataVersion();
        SummaryHandoffState state = analysisRepository.findForHandoff(
                        analysisId, consentVersion, AiDataNotice.hash(consentVersion))
                .orElseThrow(SummaryHandoffAbandonedException::staleClaim);

        if (!PullRequestAnalysisStatus.RUNNING.name().equals(state.getStatus())
                || !workerId.equals(state.getClaimedBy())) {
            throw SummaryHandoffAbandonedException.staleClaim();
        }
        if (state.getProjectDeleted()) {
            throw new SummaryHandoffAbandonedException(SummaryFailureCode.PROJECT_DELETED);
        }
        if (state.getRepositoryUnlinked()) {
            throw new SummaryHandoffAbandonedException(SummaryFailureCode.REPOSITORY_UNLINKED);
        }
        if (!"CONNECTED".equals(state.getGithubConnectionStatus())) {
            throw new SummaryHandoffAbandonedException(SummaryFailureCode.GITHUB_DISCONNECTED);
        }
        if (!state.getConsented()) {
            throw new AiDataConsentRequiredException();
        }
    }
}
