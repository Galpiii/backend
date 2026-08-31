package com.github.galpiii.galpi.domain.pullrequest.support;

import com.github.galpiii.galpi.domain.consent.AiDataNotice;
import com.github.galpiii.galpi.domain.consent.config.ConsentProperties;
import com.github.galpiii.galpi.domain.consent.exception.AiDataConsentRequiredException;
import com.github.galpiii.galpi.domain.pullrequest.entity.SummaryFailureCode;
import com.github.galpiii.galpi.domain.pullrequest.repository.PullRequestAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@DisplayName("SummaryHandoffGuard — AI 전송 직전 관문")
class SummaryHandoffGuardTest {

    private static final Long ANALYSIS_ID = 10L;
    private static final String WORKER_ID = "worker-1";

    private final PullRequestAnalysisRepository analysisRepository =
            mock(PullRequestAnalysisRepository.class);
    private final SummaryHandoffState state = mock(SummaryHandoffState.class);
    private final ConsentProperties consentProperties =
            new ConsentProperties(AiDataNotice.CURRENT_VERSION);
    private final SummaryHandoffGuard guard =
            new SummaryHandoffGuard(analysisRepository, consentProperties);

    @BeforeEach
    void setUp() {
        given(analysisRepository.findForHandoff(
                ANALYSIS_ID, AiDataNotice.CURRENT_VERSION,
                AiDataNotice.hash(AiDataNotice.CURRENT_VERSION)))
                .willReturn(Optional.of(state));
        given(state.getStatus()).willReturn("RUNNING");
        given(state.getClaimedBy()).willReturn(WORKER_ID);
        given(state.getGithubConnectionStatus()).willReturn("CONNECTED");
        given(state.getConsented()).willReturn(true);
    }

    @Test
    @DisplayName("claim·프로젝트·저장소·GitHub 연결·현재 동의가 모두 유효하면 통과한다")
    void passesOnlyWithCurrentBasis() {
        assertThatCode(() -> guard.check(ANALYSIS_ID, WORKER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("선점을 잃은 워커는 이유 코드를 남기지 않고 조용히 멈춘다")
    void rejectsStaleClaim() {
        given(state.getClaimedBy()).willReturn("another-worker");

        assertThatThrownBy(() -> guard.check(ANALYSIS_ID, WORKER_ID))
                .isInstanceOf(SummaryHandoffAbandonedException.class)
                .hasFieldOrPropertyWithValue("reason", null);
    }

    @Test
    @DisplayName("프로젝트가 삭제됐으면 전송을 막는다")
    void rejectsDeletedProject() {
        given(state.getProjectDeleted()).willReturn(true);

        assertThatThrownBy(() -> guard.check(ANALYSIS_ID, WORKER_ID))
                .isInstanceOf(SummaryHandoffAbandonedException.class)
                .hasFieldOrPropertyWithValue("reason", SummaryFailureCode.PROJECT_DELETED);
    }

    @Test
    @DisplayName("저장소 연결이 끊겼으면 전송을 막는다")
    void rejectsUnlinkedRepository() {
        given(state.getRepositoryUnlinked()).willReturn(true);

        assertThatThrownBy(() -> guard.check(ANALYSIS_ID, WORKER_ID))
                .isInstanceOf(SummaryHandoffAbandonedException.class)
                .hasFieldOrPropertyWithValue("reason", SummaryFailureCode.REPOSITORY_UNLINKED);
    }

    @Test
    @DisplayName("GitHub 연결이 끊겼으면 전송을 막는다")
    void rejectsDisconnectedRequester() {
        given(state.getGithubConnectionStatus()).willReturn("DISCONNECTED");

        assertThatThrownBy(() -> guard.check(ANALYSIS_ID, WORKER_ID))
                .isInstanceOf(SummaryHandoffAbandonedException.class)
                .hasFieldOrPropertyWithValue("reason", SummaryFailureCode.GITHUB_DISCONNECTED);
    }

    @Test
    @DisplayName("배치 시작 후 동의를 철회했으면 이 PR의 전송을 막는다")
    void rejectsRevokedConsent() {
        given(state.getConsented()).willReturn(false);

        assertThatThrownBy(() -> guard.check(ANALYSIS_ID, WORKER_ID))
                .isInstanceOf(AiDataConsentRequiredException.class);
    }
}
