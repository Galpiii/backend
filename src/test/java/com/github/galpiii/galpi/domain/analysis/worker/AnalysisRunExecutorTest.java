package com.github.galpiii.galpi.domain.analysis.worker;

import com.github.galpiii.galpi.domain.analysis.config.AnalysisWorkerProperties;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRun;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunStatus;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunTarget;
import com.github.galpiii.galpi.domain.analysis.repository.AnalysisConfigRepository;
import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunTargetRepository;
import com.github.galpiii.galpi.domain.analysis.service.AnalysisRunWriter;
import com.github.galpiii.galpi.domain.collection.RepositoryCollector;
import com.github.galpiii.galpi.domain.collection.RepositoryCollector.RepositoryCollectionResult;
import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import com.github.galpiii.galpi.domain.github.client.RateLimitRecorder;
import com.github.galpiii.galpi.domain.github.client.RateLimitSnapshot;
import com.github.galpiii.galpi.domain.github.client.dto.GithubRepositoryResponse;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.exception.GithubRateLimitedException;
import com.github.galpiii.galpi.domain.github.exception.GithubRepositoryUnavailableException;
import com.github.galpiii.galpi.domain.github.service.GithubInstallationTokenService;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AnalysisRunExecutor — 저장소 단위 부분 실패와 rate limit")
class AnalysisRunExecutorTest {

    private static final long RUN_ID = 55L;
    private static final long PERSONAL_INSTALLATION = 100L;
    private static final long ORG_INSTALLATION = 200L;

    @Mock
    private AnalysisRunTargetRepository targetRepository;
    @Mock
    private AnalysisConfigRepository configRepository;
    @Mock
    private GithubInstallationTokenService tokenService;
    @Mock
    private RepositoryCollector repositoryCollector;
    @Mock
    private AnalysisRunWriter writer;
    @Mock
    private RateLimitRecorder rateLimitRecorder;

    private AnalysisRunExecutor executor;
    private Project project;

    @BeforeEach
    void setUp() {
        executor = new AnalysisRunExecutor(targetRepository, configRepository, tokenService,
                repositoryCollector, writer, rateLimitRecorder,
                new AnalysisWorkerProperties(true, Duration.ofSeconds(5), Duration.ofMinutes(30),
                        3, 100));

        User user = User.ofGithub(999L, "wb", "wb", null, "https://avatar");
        project = Project.create(user, "갈피");
        setId(project, 3L);

        given(writer.requireRun(RUN_ID))
                .willReturn(AnalysisRun.queue(project, user, Map.of(11L, PERSONAL_INSTALLATION)));
        given(tokenService.issue(anyLong(), any())).willReturn("ghs_token");
        given(configRepository.findByProjectIdAndRepositoryId(anyLong(), anyLong()))
                .willReturn(Optional.empty());
        given(configRepository.findByProjectIdAndRepositoryIsNull(anyLong()))
                .willReturn(Optional.empty());
        given(repositoryCollector.collect(any())).willReturn(result());
    }

    @Nested
    @DisplayName("여러 installation")
    class MultipleInstallations {

        @Test
        @DisplayName("서로 다른 installation의 저장소를 묶어 토큰을 한 번씩만 발급한다")
        void issuesOneTokenPerInstallation() {
            givenTargets(
                    target(1L, 11L, "wb/personal", PERSONAL_INSTALLATION),
                    target(2L, 22L, "acme/api", ORG_INSTALLATION),
                    target(3L, 33L, "acme/web", ORG_INSTALLATION));

            executor.execute(RUN_ID);

            verify(tokenService).issue(PERSONAL_INSTALLATION, List.of(11L));
            verify(tokenService).issue(ORG_INSTALLATION, List.of(22L, 33L));
            verify(writer).finishRun(RUN_ID, AnalysisRunStatus.COMPLETED);
        }

        @Test
        @DisplayName("토큰 발급 범위는 그 그룹의 저장소로만 좁힌다")
        void narrowsTokenScopeToGroup() {
            givenTargets(
                    target(1L, 11L, "wb/personal", PERSONAL_INSTALLATION),
                    target(2L, 22L, "acme/api", ORG_INSTALLATION));

            executor.execute(RUN_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Long>> scope = ArgumentCaptor.forClass(List.class);
            verify(tokenService).issue(eq(PERSONAL_INSTALLATION), scope.capture());
            assertThat(scope.getValue()).containsExactly(11L);
        }
    }

    @Nested
    @DisplayName("부분 실패")
    class PartialFailure {

        @Test
        @DisplayName("저장소 하나가 404여도 나머지는 완료되고 전체는 PARTIALLY_COMPLETED가 된다")
        void completesOtherRepositoriesWhenOneIsGone() {
            givenTargets(
                    target(1L, 11L, "wb/gone", PERSONAL_INSTALLATION),
                    target(2L, 22L, "wb/alive", PERSONAL_INSTALLATION));
            given(repositoryCollector.collect(any()))
                    .willThrow(new GithubRepositoryUnavailableException())
                    .willReturn(result());

            executor.execute(RUN_ID);

            verify(writer).failTarget(eq(1L), anyString(), anyString());
            verify(writer).markRepositoryInaccessible(901L);
            verify(writer).completeTarget(eq(2L), any());
            verify(writer).finishRun(RUN_ID, AnalysisRunStatus.PARTIALLY_COMPLETED);
        }

        @Test
        @DisplayName("모든 저장소가 실패하면 FAILED가 된다")
        void failsWhenEveryRepositoryFails() {
            givenTargets(target(1L, 11L, "wb/gone", PERSONAL_INSTALLATION));
            given(repositoryCollector.collect(any()))
                    .willThrow(new GithubRepositoryUnavailableException());

            executor.execute(RUN_ID);

            verify(writer).finishRun(RUN_ID, AnalysisRunStatus.FAILED);
        }
    }

    @Nested
    @DisplayName("rate limit")
    class RateLimit {

        @Test
        @DisplayName("403을 맞으면 sleep 없이 중단하고 재개 시각을 남긴다")
        void stopsWithoutSleepingOnRateLimit() {
            givenTargets(
                    target(1L, 11L, "wb/first", PERSONAL_INSTALLATION),
                    target(2L, 22L, "wb/second", PERSONAL_INSTALLATION));
            willThrow(new GithubRateLimitedException(120))
                    .given(repositoryCollector).collect(any());

            long startedAt = System.nanoTime();
            executor.execute(RUN_ID);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            // reset까지 자면 2분이 걸린다. 즉시 끝나야 한다.
            assertThat(elapsed).isLessThan(Duration.ofSeconds(5));

            ArgumentCaptor<OffsetDateTime> resumeAt = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(writer).rateLimitRun(eq(RUN_ID), resumeAt.capture());
            assertThat(resumeAt.getValue()).isAfter(OffsetDateTime.now().plusSeconds(60));

            // 남은 저장소는 실패가 아니라 SKIPPED다. 재시도의 의미가 다르다.
            verify(writer).skipTarget(eq(2L), eq(List.of(IncompleteReason.RATE_LIMITED)));
            verify(writer, never()).finishRun(anyLong(), any());
        }

        @Test
        @DisplayName("남은 호출 수가 임계 이하면 다음 저장소를 시작하지 않는다")
        void stopsBeforeStartingWhenNearLimit() {
            givenTargets(
                    target(1L, 11L, "wb/first", PERSONAL_INSTALLATION),
                    target(2L, 22L, "wb/second", PERSONAL_INSTALLATION));
            given(rateLimitRecorder.latest("core")).willReturn(new RateLimitSnapshot(
                    5000, 40, 4960, Instant.now().plusSeconds(600), "core"));

            executor.execute(RUN_ID);

            // 한 건도 수집하지 않고 접는다. 절반쯤 수집한 저장소를 버리지 않기 위해서다.
            verify(repositoryCollector, never()).collect(any());
            verify(writer).skipTarget(eq(1L), eq(List.of(IncompleteReason.RATE_LIMITED)));
            verify(writer).skipTarget(eq(2L), eq(List.of(IncompleteReason.RATE_LIMITED)));
            verify(writer).rateLimitRun(eq(RUN_ID), any());
        }

        @Test
        @DisplayName("여유가 있으면 임계 검사에 걸리지 않는다")
        void proceedsWhenLimitHasHeadroom() {
            givenTargets(target(1L, 11L, "wb/first", PERSONAL_INSTALLATION));
            given(rateLimitRecorder.latest("core")).willReturn(new RateLimitSnapshot(
                    5000, 4800, 200, Instant.now().plusSeconds(600), "core"));

            executor.execute(RUN_ID);

            verify(repositoryCollector).collect(any());
            verify(writer).finishRun(RUN_ID, AnalysisRunStatus.COMPLETED);
        }
    }

    private void givenTargets(AnalysisRunTarget... targets) {
        given(targetRepository.findAllWithRepositoryByAnalysisRunId(RUN_ID))
                .willReturn(List.of(targets));
    }

    /**
     * @param targetId           analysis_run_repositories의 id
     * @param githubRepositoryId GitHub 저장소 id. 갈피 내부 id와 다르다 — 둘을 같은 값으로 두면
     *                           어느 쪽을 쓰는지 헷갈려도 테스트가 통과한다
     */
    private AnalysisRunTarget target(long targetId, long githubRepositoryId, String fullName,
                                     long installationId) {
        long repositoryId = 900 + targetId;
        GithubRepository repository = GithubRepository.link(project,
                snapshot(githubRepositoryId, fullName, installationId));
        setId(repository, repositoryId);

        AnalysisRunTarget target = AnalysisRunTarget.pending(
                AnalysisRun.queue(project, project.getUser(), Map.of()), repository,
                installationId);
        setId(target, targetId);
        return target;
    }

    private static RepositoryCollectionResult result() {
        GithubRepositoryResponse repository = new GithubRepositoryResponse(11L, "app", "wb/app",
                new GithubRepositoryResponse.Owner(1L, "wb", "User"), true, "main",
                "https://github.com/wb/app", Map.of());
        return new RepositoryCollectionResult("abc1234", "main", repository, 10, 2048, 3, 5,
                List.of());
    }

    private static RepositorySnapshot snapshot(long githubRepositoryId, String fullName,
                                               long installationId) {
        String owner = fullName.substring(0, fullName.indexOf('/'));
        String name = fullName.substring(fullName.indexOf('/') + 1);
        return new RepositorySnapshot(githubRepositoryId, installationId, owner, name, fullName,
                true, "main", "https://github.com/" + fullName);
    }

    /** 저장 전 엔티티라 id가 없다. 실행기가 id로 대상을 지정하므로 채워 넣는다. */
    private static void setId(Object entity, long id) {
        try {
            var field = entity.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
