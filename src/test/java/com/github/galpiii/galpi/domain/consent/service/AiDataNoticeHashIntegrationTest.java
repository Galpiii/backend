package com.github.galpiii.galpi.domain.consent.service;

import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunRepository;
import com.github.galpiii.galpi.domain.analysis.service.AnalysisRunService;
import com.github.galpiii.galpi.domain.consent.config.ConsentProperties;
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
 * 같은 버전 아래에서 문구가 바뀐 상황을 실제 Postgres에 붙여 확인한다.
 *
 * <p>버전은 이름일 뿐이라, 버전을 올리지 않고 문구만 고치면 사용자가 읽은 적 없는 내용에
 * 동의한 것으로 남는다. 판정에 해시를 함께 쓰는 이유이고, 그 결과 두 가지가 보장돼야 한다 —
 * 옛 문구 동의는 <b>동의로 치지 않고</b>, 그 상태에서 재동의를 시도하면 <b>조용히 넘어가지
 * 않는다</b>. 유니크 제약이 (user_id, consent_version)이라 그냥 삼키면 사용자는 재동의도 못 한
 * 채 분석만 막힌다.
 */
@DisplayName("고지 문구 해시 — 실제 Postgres")
class AiDataNoticeHashIntegrationTest extends IntegrationTestSupport {

    private static final long INSTALLATION_ID = 100L;
    private static final long GITHUB_REPOSITORY_ID = 555L;
    private static final String STALE_HASH = "옛-문구-해시";

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
        user = userRepository.save(connectedUser());
        Project project = projectRepository.save(Project.create(user, "갈피"));
        projectId = project.getId();
        repositoryRepository.save(GithubRepository.link(project, snapshot()));
        given(installationService.accessibleSnapshots(anyLong(), any()))
                .willReturn(Map.of(GITHUB_REPOSITORY_ID, snapshot()));

        // 사용자가 옛 문구에 동의해 둔 상태. 버전은 지금과 같다.
        consentRepository.saveAndFlush(
                AiDataConsent.agree(user, properties.aiDataVersion(), STALE_HASH));
    }

    @Test
    @DisplayName("버전은 그대로인데 문구만 바뀌면 동의가 풀린다")
    void staleNoticeHashInvalidatesConsent() {
        assertThat(consentService.status(user.getId()).agreed()).isFalse();
    }

    @Test
    @DisplayName("그 상태에서는 분석도 막힌다 — 읽은 적 없는 내용으로 코드를 내보낼 수 없다")
    void staleNoticeHashBlocksAnalysis() {
        assertThatThrownBy(() -> analysisRunService.create(user.getId(), projectId))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((GlobalException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_DATA_CONSENT_REQUIRED);

        assertThat(runRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("다시 동의하려 하면 500으로 크게 터진다 — 사용자가 아니라 배포가 잘못됐다")
    void reconsentFailsLoudlyWhenNoticeChangedWithoutVersionBump() {
        assertThatThrownBy(() ->
                consentService.agree(user.getId(), properties.aiDataVersion()))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_DATA_CONSENT_NOTICE_CONFLICT);

        // 삼키고 200을 돌려주면 사용자는 영원히 재동의하지 못한 채 막혀 있게 된다.
        assertThat(consentRepository.findByUserIdAndConsentVersion(
                user.getId(), properties.aiDataVersion()))
                .get()
                .satisfies(stored -> assertThat(stored.getNoticeHash()).isEqualTo(STALE_HASH));
    }

    private static User connectedUser() {
        User user = User.ofGithub(System.nanoTime(), "wb", "wb", null, "https://avatar");
        user.connectGithub();
        return user;
    }

    private static RepositorySnapshot snapshot() {
        return new RepositorySnapshot(GITHUB_REPOSITORY_ID, INSTALLATION_ID, "galpiii", "backend",
                "galpiii/backend", true, "main", "https://github.com/galpiii/backend");
    }
}
