package com.github.galpiii.galpi.domain.consent.service;

import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunRepository;
import com.github.galpiii.galpi.domain.analysis.service.AnalysisRunService;
import com.github.galpiii.galpi.domain.consent.config.ConsentProperties;
import com.github.galpiii.galpi.domain.consent.dto.AiDataConsentStatusResponse;
import com.github.galpiii.galpi.domain.consent.entity.AiDataConsent;
import com.github.galpiii.galpi.domain.consent.repository.AiDataConsentRepository;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.github.service.GithubInstallationService;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ForbiddenException;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * 첫 분석 실행 전 동의를 실제 Postgres에 붙여 확인한다.
 *
 * <p>확인하려는 것은 "막힌다"가 아니라 <b>막히면 아무것도 남지 않는다</b>이다. 동의 없이
 * 요청했을 때 {@code analysis_runs} 행이 하나라도 생기면 워커가 그것을 집어 코드를 외부로
 * 보낸다.
 */
@DisplayName("외부 AI 전송 동의 — 실제 Postgres")
class AiDataConsentIntegrationTest extends IntegrationTestSupport {

    private static final long INSTALLATION_ID = 100L;
    private static final long GITHUB_REPOSITORY_ID = 555L;
    private static final String OLD_VERSION = "2025-01-01";

    @Autowired
    private AiDataConsentService consentService;
    @Autowired
    private AnalysisRunService analysisRunService;
    @Autowired
    private AiDataConsentRepository consentRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private GithubRepositoryRepository repositoryRepository;
    @Autowired
    private AnalysisRunRepository runRepository;
    @Autowired
    private ConsentProperties properties;

    @MockitoBean
    private GithubInstallationService installationService;

    private User user;
    private Long projectId;

    @BeforeEach
    void setUp() {
        user = userRepository.save(
                User.ofGithub(System.nanoTime(), "wb", "wb", null, "https://avatar"));
        Project project = projectRepository.save(Project.create(user, "갈피"));
        projectId = project.getId();
        repositoryRepository.save(GithubRepository.link(project, snapshot()));
        given(installationService.accessibleSnapshots(anyLong(), any()))
                .willReturn(Map.of(GITHUB_REPOSITORY_ID, snapshot()));
    }

    @Test
    @DisplayName("동의 없이 분석을 실행하면 막히고 작업이 남지 않는다")
    void blocksAnalysisWithoutConsent() {
        assertThatThrownBy(() -> analysisRunService.create(user.getId(), projectId))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((GlobalException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_DATA_CONSENT_REQUIRED);

        assertThat(runRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("동의하면 분석을 실행할 수 있고 동의 버전이 함께 기록된다")
    void recordsConsentVersionAndUnblocksAnalysis() {
        AiDataConsentStatusResponse status =
                consentService.agree(user.getId(), properties.aiDataVersion());

        assertThat(status.agreed()).isTrue();
        assertThat(consentRepository.findByUserIdAndConsentVersion(
                user.getId(), properties.aiDataVersion()))
                .isPresent()
                .get()
                .satisfies(consent -> assertThat(consent.getAgreedAt()).isNotNull());

        assertThat(analysisRunService.create(user.getId(), projectId).analysisRunId()).isNotNull();
    }

    @Test
    @DisplayName("정책 버전이 올라가면 이전 버전에만 동의한 사용자는 다시 막힌다")
    void requiresReconsentAfterVersionBump() {
        // 현재 버전이 올라간 상황을 이전 버전 동의만 남겨 재현한다.
        consentRepository.save(AiDataConsent.agree(user, OLD_VERSION));

        assertThatThrownBy(() -> analysisRunService.create(user.getId(), projectId))
                .isInstanceOf(ForbiddenException.class);

        AiDataConsentStatusResponse status = consentService.status(user.getId());
        assertThat(status.agreed()).isFalse();
        assertThat(status.agreedVersion()).isEqualTo(OLD_VERSION);
        assertThat(status.currentVersion()).isEqualTo(properties.aiDataVersion());
    }

    @Test
    @DisplayName("같은 버전에 두 번 동의해도 기록은 하나다")
    void keepsSingleRowPerVersion() {
        consentService.agree(user.getId(), properties.aiDataVersion());
        consentService.agree(user.getId(), properties.aiDataVersion());

        assertThat(consentRepository.findAll()).hasSize(1);
    }

    private static RepositorySnapshot snapshot() {
        return new RepositorySnapshot(GITHUB_REPOSITORY_ID, INSTALLATION_ID, "galpiii", "backend",
                "galpiii/backend", true, "main", "https://github.com/galpiii/backend");
    }
}
