package com.github.galpiii.galpi.domain.featurematch;

import com.github.galpiii.galpi.ai.dto.FeatureMatchingResult;
import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestRepository;
import com.github.galpiii.galpi.domain.consent.config.ConsentProperties;
import com.github.galpiii.galpi.domain.consent.service.AiDataConsentService;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchFilter;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.TargetRow;
import com.github.galpiii.galpi.domain.featurematch.dto.request.FeaturePrMatchesCreateRequest;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchFailureCode;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRun;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunStatus;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchSource;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchTargetStatus;
import com.github.galpiii.galpi.domain.featurematch.entity.FeaturePrMatch;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchCurrentFeatureRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchRunRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchTargetRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeaturePrMatchRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeaturePrMatchRequirementRepository;
import com.github.galpiii.galpi.domain.featurematch.service.FeatureMatchQueryService;
import com.github.galpiii.galpi.domain.featurematch.service.FeatureMatchChangeService;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchChangesResponse;
import com.github.galpiii.galpi.domain.featurematch.service.FeatureMatchRunService;
import com.github.galpiii.galpi.domain.featurematch.service.FeatureMatchWriter;
import com.github.galpiii.galpi.domain.github.exception.GithubReauthRequiredException;
import com.github.galpiii.galpi.domain.featurematch.service.FeaturePrMatchService;
import com.github.galpiii.galpi.domain.featurematch.worker.FeatureMatchClaimer;
import com.github.galpiii.galpi.domain.featurespec.dto.request.FeatureUpdateRequest;
import com.github.galpiii.galpi.domain.featurespec.entity.Feature;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureRequirement;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureSection;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureRequirementRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureSectionRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.featurespec.service.FeatureReviewService;
import com.github.galpiii.galpi.domain.featurespec.service.SpecDocumentWriter;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.pullrequest.entity.ChangeType;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysis;
import com.github.galpiii.galpi.domain.pullrequest.entity.SummaryFailureCode;
import com.github.galpiii.galpi.domain.pullrequest.PullRequestFixture;
import com.github.galpiii.galpi.domain.pullrequest.repository.PullRequestAnalysisRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeatureMatchIntegrationTest extends IntegrationTestSupport {
    @Autowired
    FeatureMatchRunRepository runRepository;
    @Autowired
    FeatureMatchTargetRepository targetRepository;
    @Autowired
    FeaturePrMatchRepository matchRepository;
    @Autowired
    FeaturePrMatchRequirementRepository requirementMatchRepository;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    FeatureMatchRunService runs;
    @Autowired
    FeatureMatchQueryService queries;
    @Autowired
    FeatureMatchChangeService changes;
    @Autowired
    FeaturePrMatchService manual;
    @Autowired
    FeatureMatchWriter writer;
    @Autowired
    SpecDocumentWriter documentWriter;
    @Autowired
    FeatureMatchQueryRepository matches;
    @Autowired
    FeatureMatchCurrentFeatureRepository currentFeatures;
    @Autowired
    FeatureMatchClaimer claimer;
    @Autowired
    UserRepository users;
    @Autowired
    ProjectRepository projects;
    @Autowired
    GithubRepositoryRepository repositories;
    @Autowired
    PullRequestRepository prs;
    @Autowired
    PullRequestAnalysisRepository analyses;
    @Autowired
    SpecDocumentRepository documents;
    @Autowired
    FeatureRepository features;
    @Autowired
    FeatureRequirementRepository requirements;
    @Autowired
    FeatureSectionRepository sections;
    @Autowired
    FeatureReviewService review;
    @Autowired
    AiDataConsentService consent;
    @Autowired
    ConsentProperties consentProperties;
    @Autowired
    JdbcTemplate jdbc;
    User user;
    Project project;
    GithubRepository repository;
    SpecDocument document;
    Feature first, second;
    FeatureRequirement requirement, otherRequirement;
    PullRequest pr1, pr2;

    @BeforeEach
    void fixture() {
        user = PullRequestFixture.user("matching");
        user.connectGithub();
        user = users.save(user);
        project = projects.save(Project.create(user, "대조"));
        repository = repositories.save(PullRequestFixture.repository(project, "org/backend"));
        document = SpecDocument.builder().project(project).user(user).fileName("spec.pdf").build();
        document.markCompleted();
        document = documents.save(document);
        project.attachSpecDocument(document.getId());
        projects.save(project);
        var section = sections.save(FeatureSection.builder().specDocument(document).title("계정").displayOrder(0).build());
        first = features.save(Feature.builder().specDocument(document).section(section).name("회원가입").displayOrder(0).build());
        second = features.save(Feature.builder().specDocument(document).name("로그인").displayOrder(1)
                .reviewStatus(FeatureReviewStatus.USER_CONFIRMED).build());
        requirement = requirements.save(FeatureRequirement.builder().feature(first).content("이메일 중복 확인").displayOrder(0).build());
        otherRequirement = requirements.save(FeatureRequirement.builder().feature(second).content("비밀번호 확인").displayOrder(0).build());
        pr1 = pr(1, true);
        pr2 = pr(2, true);
        consent.agree(user.getId(), consentProperties.aiDataVersion());
    }

    PullRequest pr(int number, boolean completed) {
        var pr = prs.save(PullRequestFixture.pullRequest(repository, null, number, "PR " + number));
        var analysis = PullRequestAnalysis.pending(pr, 5000L, user, pr.getHeadSha());
        if (completed) {
            analysis.complete("계정 작업 " + number, ChangeType.FEATURE, "test");
        }
        analyses.save(analysis);
        return pr;
    }

    @Test
    @DisplayName("최신 실행은 생성 전 오류, 실행 상태 변화, 재실행을 반영한다")
    void restoresLatestExecutionThroughoutLifecycle() {
        error(() -> runs.latest(project.getId(), user.getId()), ErrorCode.FEATURE_MATCH_RUN_NOT_FOUND);
        long firstRun = start();
        var queued = runs.latest(project.getId(), user.getId());
        assertThat(queued.featureMatchRunId()).isEqualTo(firstRun);
        assertThat(queued.status()).isEqualTo(FeatureMatchRunStatus.QUEUED);
        assertThat(queued.specDocumentId()).isEqualTo(document.getId());
        assertThat(queued.progressPercent()).isZero();
        finish(firstRun);
        var completed = runs.latest(project.getId(), user.getId());
        assertThat(completed.status()).isEqualTo(FeatureMatchRunStatus.COMPLETED);
        assertThat(completed.progressPercent()).isEqualTo(100);
        assertThat(completed.finishedAt()).isNotNull();
        long next = start();
        assertThat(next).isGreaterThan(firstRun);
        assertThat(runs.latest(project.getId(), user.getId()).featureMatchRunId()).isEqualTo(next);
        var stranger = users.save(PullRequestFixture.user("latest-stranger"));
        error(() -> runs.latest(project.getId(), stranger.getId()), ErrorCode.PROJECT_NOT_FOUND);
        error(() -> runs.latest(Long.MAX_VALUE, user.getId()), ErrorCode.PROJECT_NOT_FOUND);
    }

    long start() {
        return runs.create(project.getId(), user.getId()).featureMatchRunId();
    }

    FeatureMatchingResult result(long featureId, Long... requirements) {
        return new FeatureMatchingResult(List.of(new FeatureMatchingResult.Match(featureId, "이메일 검증 작업이 관련됩니다.", List.of(requirements))));
    }

    void finish(long runId) {
        for (var t : claimTargets("worker", 4)) {
            assertThat(writer.prepare(t.id(), "worker")).isNotNull();
            if (t.pullRequestAnalysisId() == analyses.findByPullRequestId(pr1.getId()).orElseThrow().getId()) {
                writer.complete(t.id(), "worker", result(first.getId(), requirement.getId()));
            } else {
                writer.complete(t.id(), "worker", new FeatureMatchingResult(List.of()));
            }
        }
        assertThat(runs.status(runId, user.getId()).status()).isEqualTo(FeatureMatchRunStatus.COMPLETED);
    }

    void error(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(GlobalException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("GitHub 연결이 끊긴 사용자는 실행할 수 없다")
    void disconnectedUserCannotStartMatching() {
        user.disconnectGithub();
        users.save(user);
        assertThatThrownBy(this::start)
                .isInstanceOf(GithubReauthRequiredException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GITHUB_REAUTH_REQUIRED);
        assertThat(matches.latest(project.getId())).isNull();
    }

    @Test
    @DisplayName("완료 결과를 섹션·필터·중복 없는 집계로 조회한다")
    void completesGroupsFiltersAndDistinctCounts() {
        long id = start();
        error(() -> queries.results(project.getId(), user.getId(), null, null, null), ErrorCode.FEATURE_MATCH_RESULT_RUNNING);
        finish(id);
        var result = queries.results(project.getId(), user.getId(), null, null, null);
        assertThat(result.sections()).hasSize(2);
        assertThat(result.sections().get(1).sectionId()).isNull();
        assertThat(result.summary().matchedPullRequestCount()).isEqualTo(1);
        assertThat(result.summary().unmatchedPullRequestCount()).isEqualTo(1);
        assertThat(result.summary().attentionRequiredFeatureCount()).isEqualTo(2);
        assertThat(result.summary().unreviewedFeatureCount()).isEqualTo(1);
        var detail = queries.detail(first.getId(), user.getId(), null);
        assertThat(detail.requirements().getFirst().relatedPullRequestCount()).isEqualTo(1);
        assertThat(detail.repositories().getFirst().pullRequests().getFirst().matchedRequirements()).hasSize(1);
        assertThat(queries.results(project.getId(), user.getId(), null, null, FeatureMatchFilter.EVIDENCE_FOUND)
                .sections().getFirst().features()).hasSize(1);
        var unmatched = queries.unmatched(project.getId(), user.getId(), null, "2", 0, 20);
        assertThat(unmatched.pullRequests()).extracting("pullRequestId").containsExactly(pr2.getId());
        assertThat(queries.results(project.getId(), user.getId(), repository.getId(), "로그인", null)
                .sections().getFirst().features().getFirst().relatedPullRequestCount()).isZero();
    }

    @Test
    @DisplayName("대기 중이거나 누락된 PR 분석은 실행을 막는다")
    void blocksPendingAndMissingAnalyses() {
        pr(3, false);
        error(this::start, ErrorCode.FEATURE_MATCH_PR_NOT_READY);
        jdbc.update("DELETE FROM pull_request_analyses WHERE status='PENDING'");
        error(this::start, ErrorCode.FEATURE_MATCH_PR_NOT_READY);
    }

    @Test
    @DisplayName("중복 실행과 다른 사용자의 접근을 거부한다")
    void rejectsDuplicateRunAndForeignAccess() {
        long id = start();
        error(this::start, ErrorCode.FEATURE_MATCH_ALREADY_RUNNING);
        var stranger = users.save(PullRequestFixture.user("stranger"));
        error(() -> runs.status(id, stranger.getId()), ErrorCode.FEATURE_MATCH_RUN_NOT_FOUND);
        error(() -> queries.detail(first.getId(), stranger.getId(), null), ErrorCode.FEATURE_NOT_ACCESSIBLE);
    }

    @Test
    @DisplayName("사용자 일괄 연결은 원자적이며 재실행 후에도 유지된다")
    void manualBatchRollsBackAndSurvivesRerun() {
        finish(start());
        error(() -> manual.create(second.getId(), user.getId(), new FeaturePrMatchesCreateRequest(List.of(pr1.getId(), 999999L))),
                ErrorCode.PULL_REQUEST_NOT_FOUND);
        assertThat(queries.detail(second.getId(), user.getId(), null).relatedPullRequestCount()).isZero();
        var created = manual.create(second.getId(), user.getId(), new FeaturePrMatchesCreateRequest(List.of(pr1.getId(), pr2.getId())));
        assertThat(created.createdMatches()).hasSize(2);
        assertThat(queries.results(project.getId(), user.getId(), null, null, null).summary().matchedPullRequestCount()).isEqualTo(2);
        error(() -> manual.create(second.getId(), user.getId(), new FeaturePrMatchesCreateRequest(List.of(pr1.getId()))),
                ErrorCode.FEATURE_MATCH_DUPLICATED);
        long next = start();
        for (var t : claimTargets("next", 4)) {
            writer.prepare(t.id(), "next");
            writer.complete(t.id(), "next", result(second.getId(), otherRequirement.getId()));
        }
        assertThat(runs.status(next, user.getId()).status()).isEqualTo(FeatureMatchRunStatus.COMPLETED);
        var detail = queries.detail(second.getId(), user.getId(), null);
        assertThat(detail.relatedPullRequestCount()).isEqualTo(2);
        assertThat(detail.repositories().getFirst().pullRequests()).allMatch(m -> m.source() == FeatureMatchSource.USER);
        manual.delete(created.createdMatches().getFirst().matchId(), user.getId());
        assertThat(prs.existsById(pr1.getId())).isTrue();
        assertThat(queries.detail(second.getId(), user.getId(), null).relatedPullRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("외부 요구사항만 제외하고 유효한 기능 연결은 보존한다")
    void discardsForeignRequirementButPreservesValidFeatureMatch() {
        long id = start();
        var target = claimTargets("invalid", 4).getFirst();
        writer.prepare(target.id(), "invalid");
        writer.complete(target.id(), "invalid", result(first.getId(), otherRequirement.getId()));
        assertThat(matches.matches(document.getId())).hasSize(1);
        assertThat(matches.requirementLinks(document.getId())).isEmpty();
        assertThat(matches.target(target.id()).status()).isEqualTo(FeatureMatchTargetStatus.COMPLETED);
    }

    @Test
    @DisplayName("검토 상태 변경은 유지하고 내용 변경은 오래된 결과로 표시한다")
    void completedReviewDoesNotInvalidateButContentEditDoes() {
        finish(start());
        review.confirm(document.getId(), user.getId(), first.getId());
        assertThat(queries.detail(first.getId(), user.getId(), null).reviewStatus()).isEqualTo(FeatureReviewStatus.USER_CONFIRMED);
        review.update(document.getId(), user.getId(), first.getId(), new FeatureUpdateRequest("가입 수정", null));
        assertThat(queries.detail(first.getId(), user.getId(), null).freshness())
                .isEqualTo(FeatureMatchChangesResponse.Freshness.STALE);
        assertThat(changes.get(project.getId(), user.getId()).fullRequired()).isTrue();
        error(() -> runs.createPartial(project.getId(), user.getId()), ErrorCode.FEATURE_MATCH_FULL_REQUIRED);
    }

    @Test
    @DisplayName("변경된 PR 한 건만 재대조하고 다른 PR 결과는 보존한다")
    void partialRerunsOnlyChangedPullRequest() {
        long base = start();
        finish(base);
        var analysis = analyses.findByPullRequestId(pr2.getId()).orElseThrow();
        analysis.complete("다시 분석된 로그인 작업", ChangeType.FEATURE, "test");
        analyses.save(analysis);

        var change = changes.get(project.getId(), user.getId());
        assertThat(change.freshness()).isEqualTo(FeatureMatchChangesResponse.Freshness.STALE);
        assertThat(change.changedPullRequests()).extracting(item -> item.pullRequestId())
                .containsExactly(pr2.getId());

        var partial = runs.createPartial(project.getId(), user.getId());
        assertThat(partial.baseRunId()).isEqualTo(base);
        assertThat(partial.eligiblePullRequestCount()).isEqualTo(1);
        var target = claimTargets("partial", 4).getFirst();
        assertThat(writer.prepare(target.id(), "partial")).isNotNull();
        writer.complete(target.id(), "partial", result(second.getId(), otherRequirement.getId()));

        assertThat(runs.status(partial.featureMatchRunId(), user.getId()).status())
                .isEqualTo(FeatureMatchRunStatus.COMPLETED);
        assertThat(changes.get(project.getId(), user.getId()).freshness())
                .isEqualTo(FeatureMatchChangesResponse.Freshness.CURRENT);
        assertThat(matches.matches(document.getId())).extracting(match -> match.pullRequestId())
                .containsExactlyInAnyOrder(pr1.getId(), pr2.getId());
        error(() -> runs.createPartial(project.getId(), user.getId()), ErrorCode.FEATURE_MATCH_NO_CHANGES);
    }

    @Test
    @DisplayName("처음부터 분석이 실패한 PR은 전체 대조의 변경사항으로 다시 잡지 않는다")
    void excludedFailedAnalysisDoesNotMakeFullResultStale() {
        PullRequest excluded = pr(3, false);
        var analysis = analyses.findByPullRequestId(excluded.getId()).orElseThrow();
        analysis.fail(SummaryFailureCode.SUMMARY_LLM_FAILED, "요약 실패");
        analyses.save(analysis);

        finish(start());

        assertThat(changes.get(project.getId(), user.getId()).freshness())
                .isEqualTo(FeatureMatchChangesResponse.Freshness.CURRENT);
        assertThat(queries.results(project.getId(), user.getId(), null, null, null)
                .summary().excludedFailedPullRequestCount()).isEqualTo(1);
        error(() -> runs.createPartial(project.getId(), user.getId()), ErrorCode.FEATURE_MATCH_NO_CHANGES);
    }

    @Test
    @DisplayName("원본이 그대로인 PR의 재분석 실패는 이전 결과를 유지하고 CURRENT로 표시한다")
    void failedReanalysisKeepsPreviousMatchCurrent() {
        finish(start());
        var analysis = analyses.findByPullRequestId(pr1.getId()).orElseThrow();
        analysis.fail(SummaryFailureCode.SUMMARY_LLM_FAILED, "요약 실패");
        analyses.save(analysis);

        assertThat(queries.detail(first.getId(), user.getId(), null).relatedPullRequestCount()).isEqualTo(1);
        assertThat(changes.get(project.getId(), user.getId()).changedPullRequests()).isEmpty();
        assertThat(changes.get(project.getId(), user.getId()).freshness())
                .isEqualTo(FeatureMatchChangesResponse.Freshness.CURRENT);
        assertThat(changes.get(project.getId(), user.getId()).staleReasons()).isEmpty();
        var summary = queries.results(project.getId(), user.getId(), null, null, null);
        assertThat(summary.summary().eligiblePullRequestCount()).isEqualTo(1);
        assertThat(summary.summary().matchedPullRequestCount()).isEqualTo(1);
        assertThat(summary.summary().excludedFailedPullRequestCount()).isEqualTo(1);
        assertThat(summary.repositories().getFirst().matchedPullRequestCount()).isEqualTo(1);
        error(() -> runs.createPartial(project.getId(), user.getId()), ErrorCode.FEATURE_MATCH_NO_CHANGES);

        finish(start());
        assertThat(queries.detail(first.getId(), user.getId(), null).relatedPullRequestCount()).isEqualTo(1);
        assertThat(changes.get(project.getId(), user.getId()).freshness())
                .isEqualTo(FeatureMatchChangesResponse.Freshness.CURRENT);
    }

    @Test
    @DisplayName("V17에서 이관된 정상 결과는 PR 원본 기준을 복구한 뒤 재분석 실패해도 CURRENT를 유지한다")
    void backfillsLegacyPullRequestSourceHash() {
        finish(start());
        jdbc.update("UPDATE feature_match_current_pull_requests SET source_snapshot_hash=NULL WHERE pull_request_id=?",
                pr1.getId());
        changes.backfillLegacySnapshots();
        var analysis = analyses.findByPullRequestId(pr1.getId()).orElseThrow();
        analysis.fail(SummaryFailureCode.PATCH_UNAVAILABLE, "patch를 받을 수 없음");
        analyses.save(analysis);

        assertThat(changes.get(project.getId(), user.getId()).freshness())
                .isEqualTo(FeatureMatchChangesResponse.Freshness.CURRENT);
    }

    @Test
    @DisplayName("PR 원본 변경 뒤 재분석이 실패하면 이전 연결을 유지하되 STALE로 표시한다")
    void changedSourceAndFailedReanalysisIsStale() {
        finish(start());
        jdbc.update("UPDATE pull_requests SET title='변경된 PR' WHERE id=?", pr1.getId());
        var analysis = analyses.findByPullRequestId(pr1.getId()).orElseThrow();
        analysis.fail(SummaryFailureCode.PATCH_UNAVAILABLE, "patch를 받을 수 없음");
        analyses.save(analysis);

        var change = changes.get(project.getId(), user.getId());
        assertThat(change.freshness()).isEqualTo(FeatureMatchChangesResponse.Freshness.STALE);
        assertThat(change.staleReasons()).containsExactly(FeatureMatchChangesResponse.StaleReason.PULL_REQUEST_CHANGED);
        assertThat(change.changedPullRequests()).isEmpty();
        assertThat(change.changedPullRequestCount()).isZero();
        assertThat(change.rerunBlockReasons()).containsExactly(
                FeatureMatchChangesResponse.RerunBlockReason.PR_REANALYSIS_REQUIRED);
        assertThat(queries.detail(first.getId(), user.getId(), null).relatedPullRequestCount()).isEqualTo(1);
        error(() -> runs.createPartial(project.getId(), user.getId()), ErrorCode.FEATURE_MATCH_PR_NOT_READY);
        pr(3, false);
        assertThat(changes.get(project.getId(), user.getId()).rerunBlockReasons()).containsExactly(
                FeatureMatchChangesResponse.RerunBlockReason.PR_ANALYSIS_NOT_READY,
                FeatureMatchChangesResponse.RerunBlockReason.PR_REANALYSIS_REQUIRED);
    }

    @Test
    @DisplayName("새 PR의 분석 실패는 결과를 낡게 만들지 않고 분석 완료 후에만 변경으로 감지한다")
    void newlyFailedPullRequestBecomesChangeOnlyAfterAnalysisCompletes() {
        finish(start());
        PullRequest newPr = pr(3, false);
        var pending = changes.get(project.getId(), user.getId());
        assertThat(pending.freshness()).isEqualTo(FeatureMatchChangesResponse.Freshness.CURRENT);
        assertThat(pending.rerunBlockReasons()).containsExactly(
                FeatureMatchChangesResponse.RerunBlockReason.PR_ANALYSIS_NOT_READY);
        var analysis = analyses.findByPullRequestId(newPr.getId()).orElseThrow();
        analysis.fail(SummaryFailureCode.PATCH_UNAVAILABLE, "patch를 받을 수 없음");
        analyses.save(analysis);

        var change = changes.get(project.getId(), user.getId());
        assertThat(change.freshness()).isEqualTo(FeatureMatchChangesResponse.Freshness.CURRENT);
        assertThat(change.changedPullRequests()).isEmpty();
        assertThat(change.staleReasons()).isEmpty();
        assertThat(change.rerunBlockReasons()).isEmpty();
        error(() -> runs.createPartial(project.getId(), user.getId()), ErrorCode.FEATURE_MATCH_NO_CHANGES);

        analysis.complete("새 작업", ChangeType.FEATURE, "test");
        analyses.save(analysis);
        var ready = changes.get(project.getId(), user.getId());
        assertThat(ready.freshness()).isEqualTo(FeatureMatchChangesResponse.Freshness.STALE);
        assertThat(ready.changedPullRequests()).extracting(item -> item.pullRequestId())
                .containsExactly(newPr.getId());
    }

    @Test
    @DisplayName("새 PR 분석이 실패해도 준비된 다른 PR은 부분 대조하고 제외 건수를 기록한다")
    void newlyFailedPullRequestDoesNotBlockReadyPartialTarget() {
        finish(start());
        PullRequest newPr = pr(3, false);
        var failed = analyses.findByPullRequestId(newPr.getId()).orElseThrow();
        failed.fail(SummaryFailureCode.PATCH_UNAVAILABLE, "patch를 받을 수 없음");
        analyses.save(failed);
        var ready = analyses.findByPullRequestId(pr2.getId()).orElseThrow();
        ready.complete("변경된 작업", ChangeType.FEATURE, "test");
        analyses.save(ready);

        var change = changes.get(project.getId(), user.getId());
        assertThat(change.changedPullRequests()).extracting(item -> item.pullRequestId())
                .containsExactly(pr2.getId());
        var partial = runs.createPartial(project.getId(), user.getId());
        assertThat(partial.eligiblePullRequestCount()).isEqualTo(1);
        assertThat(partial.excludedFailedPullRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("원본 변경 뒤 재분석 실패한 PR은 보류하되 준비된 다른 PR은 부분 대조한다")
    void changedFailedAnalysisDoesNotBlockReadyPartialTarget() {
        finish(start());
        jdbc.update("UPDATE pull_requests SET title='변경된 PR' WHERE id=?", pr1.getId());
        var failed = analyses.findByPullRequestId(pr1.getId()).orElseThrow();
        failed.fail(SummaryFailureCode.PATCH_UNAVAILABLE, "patch를 받을 수 없음");
        analyses.save(failed);
        var ready = analyses.findByPullRequestId(pr2.getId()).orElseThrow();
        ready.complete("변경된 작업", ChangeType.FEATURE, "test");
        analyses.save(ready);

        var change = changes.get(project.getId(), user.getId());
        assertThat(change.changedPullRequests()).extracting(item -> item.pullRequestId())
                .containsExactly(pr2.getId());
        assertThat(change.rerunBlockReasons()).containsExactly(
                FeatureMatchChangesResponse.RerunBlockReason.PR_REANALYSIS_REQUIRED);
        assertThat(runs.createPartial(project.getId(), user.getId()).eligiblePullRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("재분석 실패 PR이 있어도 기능 삭제와 준비된 PR 대조는 진행한다")
    void retryRequiredAnalysisDoesNotBlockOtherPartialWork() {
        finish(start());
        var failed = analyses.findByPullRequestId(pr1.getId()).orElseThrow();
        failed.fail(SummaryFailureCode.PATCH_UNAVAILABLE, "patch를 받을 수 없음");
        analyses.save(failed);
        review.delete(document.getId(), user.getId(), second.getId());
        var changed = analyses.findByPullRequestId(pr2.getId()).orElseThrow();
        changed.complete("변경된 로그인 작업", ChangeType.FEATURE, "test");
        analyses.save(changed);

        var before = changes.get(project.getId(), user.getId());
        assertThat(before.changedPullRequests()).extracting(item -> item.pullRequestId())
                .containsExactly(pr2.getId());
        assertThat(before.removedFeatureIds()).containsExactly(second.getId());
        assertThat(before.changedPullRequestCount()).isEqualTo(1);

        var partial = runs.createPartial(project.getId(), user.getId());
        assertThat(partial.eligiblePullRequestCount()).isEqualTo(1);
        assertThat(partial.excludedFailedPullRequestCount()).isEqualTo(1);
        var target = claimTargets("retry-required", 4).getFirst();
        writer.complete(target.id(), "retry-required", new FeatureMatchingResult(List.of()));

        var after = changes.get(project.getId(), user.getId());
        assertThat(after.freshness()).isEqualTo(FeatureMatchChangesResponse.Freshness.CURRENT);
        assertThat(after.changedPullRequests()).isEmpty();
        assertThat(after.removedFeatureIds()).isEmpty();
        assertThat(queries.detail(first.getId(), user.getId(), null).relatedPullRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("재분석 실패 PR이 있어도 기능 삭제만 하는 부분 실행은 즉시 완료한다")
    void retryRequiredAnalysisDoesNotBlockDeletionOnlyPartial() {
        finish(start());
        var failed = analyses.findByPullRequestId(pr1.getId()).orElseThrow();
        failed.fail(SummaryFailureCode.PATCH_UNAVAILABLE, "patch를 받을 수 없음");
        analyses.save(failed);
        review.delete(document.getId(), user.getId(), second.getId());

        var partial = runs.createPartial(project.getId(), user.getId());
        assertThat(partial.status()).isEqualTo(FeatureMatchRunStatus.COMPLETED);
        assertThat(partial.eligiblePullRequestCount()).isZero();
        assertThat(partial.excludedFailedPullRequestCount()).isEqualTo(1);
        assertThat(changes.get(project.getId(), user.getId()).freshness())
                .isEqualTo(FeatureMatchChangesResponse.Freshness.CURRENT);
    }

    @Test
    @DisplayName("PR 분석이 하나라도 진행 중이면 준비된 PR도 목록에서 제외하고 FULL·PARTIAL 모두 거절한다")
    void partialRejectsMixedReadyAndPendingChanges() {
        long base = start();
        finish(base);
        pr(3, false);
        var analysis = analyses.findByPullRequestId(pr2.getId()).orElseThrow();
        analysis.complete("변경된 작업", ChangeType.FEATURE, "test");
        analyses.save(analysis);

        var change = changes.get(project.getId(), user.getId());
        assertThat(change.freshness()).isEqualTo(FeatureMatchChangesResponse.Freshness.STALE);
        assertThat(change.changedPullRequests()).isEmpty();
        assertThat(change.rerunBlockReasons()).containsExactly(
                FeatureMatchChangesResponse.RerunBlockReason.PR_ANALYSIS_NOT_READY);
        error(this::start, ErrorCode.FEATURE_MATCH_PR_NOT_READY);
        error(() -> runs.createPartial(project.getId(), user.getId()), ErrorCode.FEATURE_MATCH_PR_NOT_READY);
        assertThat(runs.latest(project.getId(), user.getId()).featureMatchRunId()).isEqualTo(base);
    }

    @Test
    @DisplayName("변경이 기능 삭제뿐이어도 다른 PR 분석이 진행 중이면 PARTIAL을 거절한다")
    void partialRejectsPendingAnalysisDuringDeletionOnlyChange() {
        finish(start());
        review.delete(document.getId(), user.getId(), second.getId());
        pr(3, false);

        error(() -> runs.createPartial(project.getId(), user.getId()), ErrorCode.FEATURE_MATCH_PR_NOT_READY);
    }

    @Test
    @DisplayName("원본이 그대로인 PR 재분석 중에도 CURRENT를 유지하지만 실행은 거절한다")
    void pendingReanalysisBlocksRunsWithoutMakingResultStale() {
        finish(start());
        var analysis = analyses.findByPullRequestId(pr1.getId()).orElseThrow();
        analysis.requeueForNewHead(analysis.getInstallationId(), user, pr1.getHeadSha());
        analyses.save(analysis);

        var change = changes.get(project.getId(), user.getId());
        assertThat(change.freshness()).isEqualTo(FeatureMatchChangesResponse.Freshness.CURRENT);
        assertThat(change.changedPullRequests()).isEmpty();
        assertThat(change.rerunBlockReasons()).containsExactly(
                FeatureMatchChangesResponse.RerunBlockReason.PR_ANALYSIS_NOT_READY);
        error(this::start, ErrorCode.FEATURE_MATCH_PR_NOT_READY);
        error(() -> runs.createPartial(project.getId(), user.getId()), ErrorCode.FEATURE_MATCH_PR_NOT_READY);
    }

    @Test
    @DisplayName("실패한 대상을 부분 대조로 복구하면 현재 요약의 실패 건수가 사라진다")
    void partialRecoveryUpdatesCurrentFailureSummary() {
        long base = start();
        var targets = claimTargets("first-attempt", 4);
        writer.complete(targets.getFirst().id(), "first-attempt", new FeatureMatchingResult(List.of()));
        writer.fail(targets.getLast().id(), "first-attempt", FeatureMatchFailureCode.AI_RESPONSE_INVALID, false);
        assertThat(queries.results(project.getId(), user.getId(), null, null, null)
                .summary().matchingFailedPullRequestCount()).isEqualTo(1);

        var partial = runs.createPartial(project.getId(), user.getId());
        assertThat(queries.results(project.getId(), user.getId(), null, null, null)
                .summary().matchingFailedPullRequestCount()).isEqualTo(1);
        var retry = claimTargets("retry", 4).getFirst();
        writer.complete(retry.id(), "retry", new FeatureMatchingResult(List.of()));

        var result = queries.results(project.getId(), user.getId(), null, null, null);
        assertThat(result.featureMatchRunId()).isEqualTo(base);
        assertThat(result.status()).isEqualTo(FeatureMatchRunStatus.PARTIALLY_COMPLETED);
        assertThat(result.summary().matchingFailedPullRequestCount()).isZero();
        assertThat(result.changes().freshness()).isEqualTo(FeatureMatchChangesResponse.Freshness.CURRENT);
        assertThat(runs.status(partial.featureMatchRunId(), user.getId()).status())
                .isEqualTo(FeatureMatchRunStatus.COMPLETED);
    }

    @Test
    @DisplayName("재대조 실패 건수는 보존된 표시 결과와 겹칠 수 있다")
    void failedPartialAttemptOverlapsCurrentResultCounts() {
        finish(start());
        var analysis = analyses.findByPullRequestId(pr2.getId()).orElseThrow();
        analysis.complete("변경된 작업", ChangeType.FEATURE, "test");
        analyses.save(analysis);
        runs.createPartial(project.getId(), user.getId());
        var target = claimTargets("failed-partial", 4).getFirst();
        writer.fail(target.id(), "failed-partial", FeatureMatchFailureCode.AI_RESPONSE_INVALID, false);

        var summary = queries.results(project.getId(), user.getId(), null, null, null).summary();
        assertThat(summary.eligiblePullRequestCount()).isEqualTo(2);
        assertThat(summary.matchedPullRequestCount()).isEqualTo(1);
        assertThat(summary.unmatchedPullRequestCount()).isEqualTo(1);
        assertThat(summary.matchingFailedPullRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("PR 재분석이 실패해도 이전 미매칭 결과의 요약과 목록은 일치한다")
    void failedReanalysisKeepsPreviousUnmatchedResultVisible() {
        finish(start());
        var analysis = analyses.findByPullRequestId(pr2.getId()).orElseThrow();
        analysis.fail(SummaryFailureCode.PATCH_UNAVAILABLE, "patch를 받을 수 없음");
        analyses.save(analysis);

        var summary = queries.results(project.getId(), user.getId(), null, null, null).summary();
        assertThat(summary.eligiblePullRequestCount()).isEqualTo(1);
        assertThat(summary.unmatchedPullRequestCount()).isEqualTo(1);
        assertThat(summary.excludedFailedPullRequestCount()).isEqualTo(1);
        assertThat(queries.unmatched(project.getId(), user.getId(), null, null, 0, 20).pullRequests())
                .extracting(item -> item.pullRequestId()).containsExactly(pr2.getId());
    }

    @Test
    @DisplayName("전체 대조 중 일부 성공 후 연결이 끊기면 성공한 실행을 현재 기준으로 승격한다")
    void cancelledFullWithCompletedTargetBecomesCurrentBase() {
        long previous = start();
        finish(previous);
        long next = start();
        var targets = claimTargets("disconnect", 4);
        writer.complete(targets.getFirst().id(), "disconnect", new FeatureMatchingResult(List.of()));
        user.disconnectGithub();
        users.save(user);
        assertThat(writer.prepare(targets.getLast().id(), "disconnect")).isNull();

        var status = runs.status(next, user.getId());
        assertThat(status.status()).isEqualTo(FeatureMatchRunStatus.PARTIALLY_COMPLETED);
        assertThat(status.failureCode()).isEqualTo(FeatureMatchFailureCode.GITHUB_DISCONNECTED);
        assertThat(queries.results(project.getId(), user.getId(), null, null, null)
                .featureMatchRunId()).isEqualTo(next);
        assertThat(runRepository.existsById(previous)).isFalse();
    }

    @Test
    @DisplayName("기능 삭제와 PR 변경이 함께 있으면 변경 PR만 다시 대조한다")
    void partialHandlesFeatureDeletionAndChangedPullRequestTogether() {
        finish(start());
        review.delete(document.getId(), user.getId(), first.getId());
        var analysis = analyses.findByPullRequestId(pr2.getId()).orElseThrow();
        analysis.complete("변경된 로그인 작업", ChangeType.FEATURE, "test");
        analyses.save(analysis);

        var change = changes.get(project.getId(), user.getId());
        assertThat(change.fullRequired()).isFalse();
        assertThat(change.removedFeatureIds()).containsExactly(first.getId());
        assertThat(change.changedPullRequests()).extracting(item -> item.pullRequestId())
                .containsExactly(pr2.getId());

        var partial = runs.createPartial(project.getId(), user.getId());
        assertThat(partial.eligiblePullRequestCount()).isEqualTo(1);
        var target = claimTargets("deletion-and-pr", 4).getFirst();
        writer.complete(target.id(), "deletion-and-pr", result(second.getId(), otherRequirement.getId()));
        assertThat(changes.get(project.getId(), user.getId()).freshness())
                .isEqualTo(FeatureMatchChangesResponse.Freshness.CURRENT);
    }

    @Test
    @DisplayName("저장소 연결 해제는 삭제된 PR로 표시하고 부분 실행에서 현재 스냅샷을 정리한다")
    void unlinkingRepositoryRemovesCurrentPullRequestSnapshots() {
        finish(start());
        repository.unlink();
        repositories.save(repository);

        var change = changes.get(project.getId(), user.getId());
        assertThat(change.changedPullRequests()).isEmpty();
        assertThat(change.removedPullRequestIds()).containsExactlyInAnyOrder(pr1.getId(), pr2.getId());
        assertThat(change.changedPullRequestCount()).isEqualTo(2);

        var partial = runs.createPartial(project.getId(), user.getId());
        assertThat(partial.status()).isEqualTo(FeatureMatchRunStatus.COMPLETED);
        assertThat(changes.get(project.getId(), user.getId()).freshness())
                .isEqualTo(FeatureMatchChangesResponse.Freshness.CURRENT);
        assertThat(matches.matches(document.getId())).isEmpty();
    }

    @Test
    @DisplayName("기능 삭제만 있으면 LLM 대상 없이 부분 실행으로 정리한다")
    void deletedFeatureNeedsNoAiTarget() {
        long base = start();
        finish(base);
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> currentFeatures.deleteAllByProjectId(project.getId()));
        changes.backfillLegacySnapshots();
        review.delete(document.getId(), user.getId(), first.getId());

        var change = changes.get(project.getId(), user.getId());
        assertThat(change.fullRequired()).isFalse();
        assertThat(change.removedFeatureIds()).containsExactly(first.getId());
        var partial = runs.createPartial(project.getId(), user.getId());
        assertThat(partial.eligiblePullRequestCount()).isZero();
        assertThat(runs.status(partial.featureMatchRunId(), user.getId()).status())
                .isEqualTo(FeatureMatchRunStatus.COMPLETED);
        assertThat(changes.get(project.getId(), user.getId()).freshness())
                .isEqualTo(FeatureMatchChangesResponse.Freshness.CURRENT);
        review.delete(document.getId(), user.getId(), second.getId());
        var lastDeletion = runs.createPartial(project.getId(), user.getId());
        assertThat(lastDeletion.featureCount()).isZero();
        assertThat(queries.results(project.getId(), user.getId(), null, null, null)
                .summary().totalFeatureCount()).isZero();
    }

    @Test
    @DisplayName("새 전체 실행이 일부 실패해도 실패한 PR의 기존 결과를 유지한다")
    void partialFullKeepsPreviousResultForFailedTarget() {
        long previous = start();
        finish(previous);
        review.update(document.getId(), user.getId(), first.getId(),
                new FeatureUpdateRequest("회원가입 변경", null));

        long next = start();
        var targets = claimTargets("new-full", 4);
        long firstAnalysisId = analyses.findByPullRequestId(pr1.getId()).orElseThrow().getId();
        var firstTarget = targets.stream().filter(target -> target.pullRequestAnalysisId() == firstAnalysisId)
                .findFirst().orElseThrow();
        var otherTarget = targets.stream().filter(target -> target.pullRequestAnalysisId() != firstAnalysisId)
                .findFirst().orElseThrow();
        writer.complete(otherTarget.id(), "new-full", new FeatureMatchingResult(List.of()));
        writer.fail(firstTarget.id(), "new-full", FeatureMatchFailureCode.AI_RESPONSE_INVALID, false);

        assertThat(runs.status(next, user.getId()).status())
                .isEqualTo(FeatureMatchRunStatus.PARTIALLY_COMPLETED);
        assertThat(runRepository.existsById(previous)).isFalse();
        assertThat(queries.detail(first.getId(), user.getId(), null).relatedPullRequestCount()).isEqualTo(1);
        assertThat(changes.get(project.getId(), user.getId()).freshness())
                .isEqualTo(FeatureMatchChangesResponse.Freshness.STALE);
    }

    @Test
    @DisplayName("부분 대조 실행 중 PR 분석이 다시 바뀌면 결과를 반영하되 오래된 상태로 표시한다")
    void partialResultBecomesStaleWhenPrChangesDuringRun() {
        finish(start());
        var analysis = analyses.findByPullRequestId(pr2.getId()).orElseThrow();
        analysis.complete("첫 변경", ChangeType.FEATURE, "test");
        analyses.save(analysis);
        var partial = runs.createPartial(project.getId(), user.getId());
        var target = claimTargets("changing", 4).getFirst();
        assertThat(writer.prepare(target.id(), "changing")).isNotNull();
        analysis.complete("실행 중 두 번째 변경", ChangeType.FEATURE, "test");
        analyses.save(analysis);
        writer.complete(target.id(), "changing", result(second.getId(), otherRequirement.getId()));

        assertThat(runs.status(partial.featureMatchRunId(), user.getId()).status())
                .isEqualTo(FeatureMatchRunStatus.COMPLETED);
        assertThat(queries.detail(second.getId(), user.getId(), null).relatedPullRequestCount()).isEqualTo(1);
        assertThat(changes.get(project.getId(), user.getId()).changedPullRequests())
                .extracting(item -> item.pullRequestId()).containsExactly(pr2.getId());
    }

    @Test
    @DisplayName("실행 중 변경된 입력의 결과는 반영하고 오래된 결과로 표시한다")
    void staleInputIsSavedAndMarkedStale() {
        long id = start();
        var claimed = claimTargets("stale", 4);
        var t = claimed.getFirst();
        assertThat(writer.prepare(t.id(), "stale")).isNotNull();
        jdbc.update("UPDATE features SET name='변경' WHERE id=?", first.getId());
        writer.complete(t.id(), "stale", result(first.getId(), requirement.getId()));
        assertThat(matches.target(t.id()).status()).isEqualTo(FeatureMatchTargetStatus.COMPLETED);
        assertThat(matches.matches(document.getId())).hasSize(1);
        writer.complete(claimed.get(1).id(), "stale", new FeatureMatchingResult(List.of()));
        assertThat(runs.status(id, user.getId()).status()).isEqualTo(FeatureMatchRunStatus.COMPLETED);
        assertThat(changes.get(project.getId(), user.getId()).freshness())
                .isEqualTo(FeatureMatchChangesResponse.Freshness.STALE);
    }

    @Test
    @DisplayName("만료된 선점은 새 선점 결과를 덮어쓸 수 없다")
    void expiredClaimCannotOverwriteNewClaim() {
        start();
        var firstClaim = claimTargets("old", 4);
        jdbc.update("UPDATE feature_match_targets SET claimed_at=now()-interval '1 hour'");
        var secondClaim = claimTargets("new", 4);
        assertThat(secondClaim).hasSize(2);
        writer.complete(firstClaim.getFirst().id(), "old", result(first.getId(), requirement.getId()));
        assertThat(matches.target(firstClaim.getFirst().id()).claimedBy()).isEqualTo("new");
        assertThat(matches.matches(document.getId())).isEmpty();
    }

    @Test
    @DisplayName("실패 대상을 미매칭 PR로 집계하지 않는다")
    void partialResultsDoNotCallFailedTargetUnmatched() {
        long id = start();
        var targets = claimTargets("partial", 4);
        writer.complete(targets.getFirst().id(), "partial", new FeatureMatchingResult(List.of()));
        writer.fail(targets.getLast().id(), "partial", FeatureMatchFailureCode.AI_RESPONSE_INVALID, false);
        var status = runs.status(id, user.getId());
        assertThat(status.status()).isEqualTo(FeatureMatchRunStatus.PARTIALLY_COMPLETED);
        assertThat(status.progressPercent()).isEqualTo(100);
        var result = queries.results(project.getId(), user.getId(), null, null, null);
        assertThat(result.summary().unmatchedPullRequestCount()).isEqualTo(1);
        assertThat(result.summary().matchingFailedPullRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("새 전체 실행이 완료되면 이전 세대만 정리하고 사용자 연결을 유지한다")
    void rerunDeletesOnlyPreviousProjectExecutionAndKeepsManualLinks() {
        long previous = start();
        finish(previous);
        var oldTargets = matches.targets(previous);
        var manualResult = manual.create(second.getId(), user.getId(),
                new FeaturePrMatchesCreateRequest(List.of(pr2.getId())));
        long manualId = manualResult.createdMatches().getFirst().matchId();

        Project otherProject = projects.save(Project.create(user, "다른 프로젝트"));
        SpecDocument otherDocument = documents.save(SpecDocument.builder()
                .project(otherProject).user(user).fileName("other.pdf").build());
        FeatureMatchRun otherRun = runRepository.save(FeatureMatchRun.queue(
                otherProject, otherDocument, user, "a".repeat(64), 1, 1, 0, 0));
        assertThat(requirementMatchRepository.count()).isEqualTo(1);

        long next = start();
        assertThat(runRepository.existsById(previous)).isTrue();
        finish(next);

        assertThat(next).isNotEqualTo(previous);
        assertThat(runRepository.existsById(previous)).isFalse();
        assertThat(runRepository.existsById(otherRun.getId())).isTrue();
        assertThat(runRepository.count()).isEqualTo(2);
        assertThat(oldTargets).allSatisfy(target ->
                assertThat(targetRepository.existsById(target.id())).isFalse());
        assertThat(targetRepository.count()).isEqualTo(2);
        assertThat(requirementMatchRepository.count()).isEqualTo(1);
        assertThat(matchRepository.findAll()).extracting(FeaturePrMatch::getId).contains(manualId);
        assertThat(prs.existsById(pr1.getId())).isTrue();
        assertThat(analyses.count()).isEqualTo(2);
        assertThat(requirements.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("유효하지 않은 재실행은 이전 결과를 보존한다")
    void invalidRerunPreservesPreviousResult() {
        long previous = start();
        finish(previous);
        var targetIds = matches.targets(previous).stream().map(target -> target.id()).toList();
        pr(3, false);

        error(this::start, ErrorCode.FEATURE_MATCH_PR_NOT_READY);

        assertThat(matches.latest(project.getId()).id()).isEqualTo(previous);
        assertThat(matches.targets(previous)).extracting(target -> target.id()).containsExactlyElementsOf(targetIds);
        assertThat(matchRepository.count()).isEqualTo(1);
        assertThat(requirementMatchRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("재실행 생성 롤백 시 이전 실행과 하위 데이터를 복구한다")
    void rollbackAfterReplacementRestoresPreviousExecutionAndChildren() {
        long previous = start();
        finish(previous);
        var targetIds = matches.targets(previous).stream().map(target -> target.id()).toList();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            start();
            assertThat(runRepository.existsById(previous)).isTrue();
            throw new IllegalStateException("새 실행 트랜잭션 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(matches.latest(project.getId()).id()).isEqualTo(previous);
        assertThat(runRepository.count()).isEqualTo(1);
        assertThat(matches.targets(previous)).extracting(target -> target.id()).containsExactlyElementsOf(targetIds);
        assertThat(matchRepository.count()).isEqualTo(1);
        assertThat(requirementMatchRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("종료된 작업의 늦은 완료는 다음 실행 결과를 덮지 않는다")
    void lateWorkerCannotRestoreDeletedExecution() {
        long previous = start();
        var claimed = claimTargets("old", 4);
        var target = claimed.getFirst();
        assertThat(writer.prepare(target.id(), "old")).isNotNull();
        jdbc.update("UPDATE features SET name = '변경된 기능' WHERE id = ?", first.getId());
        writer.complete(target.id(), "old", result(first.getId(), requirement.getId()));
        writer.complete(claimed.get(1).id(), "old", new FeatureMatchingResult(List.of()));
        assertThat(runs.status(previous, user.getId()).status()).isEqualTo(FeatureMatchRunStatus.COMPLETED);
        long next = start();

        writer.complete(target.id(), "old", result(first.getId(), requirement.getId()));
        writer.fail(target.id(), "old", FeatureMatchFailureCode.AI_CALL_FAILED, true);
        claimer.heartbeat(target.id(), "old");

        assertThat(runRepository.existsById(previous)).isTrue();
        assertThat(matchRepository.count()).isEqualTo(1);
        assertThat(runs.status(next, user.getId()).status()).isEqualTo(FeatureMatchRunStatus.QUEUED);
        assertThat(matches.targets(next)).allMatch(row -> row.status() == FeatureMatchTargetStatus.PENDING);
    }

    @Test
    @DisplayName("동의 철회는 취소 상태를 확정한다")
    void revokedConsentCancelsWithoutRollingBackCancellation() {
        long id = start();
        var target = claimTargets("consent", 4).getFirst();
        jdbc.update("DELETE FROM ai_data_consents WHERE user_id = ?", user.getId());

        assertThat(writer.prepare(target.id(), "consent")).isNull();
        assertThat(runs.status(id, user.getId()).status()).isEqualTo(FeatureMatchRunStatus.CANCELLED);
        assertThat(matches.targets(id)).allMatch(row -> row.status() == FeatureMatchTargetStatus.CANCELLED);
    }

    @Test
    @DisplayName("AI 연결 삭제 시 요구사항 연결만 함께 삭제하고 PR은 유지한다")
    void deletingAiLinkAlsoDeletesItsRequirementLinksButNotThePullRequest() {
        long id = start();
        finish(id);
        long matchId = matches.matches(document.getId()).getFirst().id();

        manual.delete(matchId, user.getId());

        assertThat(matchRepository.count()).isZero();
        assertThat(requirementMatchRepository.count()).isZero();
        assertThat(prs.existsById(pr1.getId())).isTrue();
        assertThat(requirements.existsById(requirement.getId())).isTrue();
    }

    @Test
    @DisplayName("동시 재실행은 새 실행 하나만 만든다")
    void concurrentRerunsLeaveOnlyOneNewExecution() throws Exception {
        long previous = start();
        finish(previous);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            java.util.concurrent.Callable<Object> request = () -> {
                barrier.await();
                try {
                    return start();
                } catch (GlobalException exception) {
                    return exception.getErrorCode();
                }
            };
            var firstRequest = pool.submit(request);
            var secondRequest = pool.submit(request);
            var outcomes = List.of(firstRequest.get(10, TimeUnit.SECONDS), secondRequest.get(10, TimeUnit.SECONDS));

            assertThat(outcomes.stream().filter(Long.class::isInstance)).hasSize(1);
            assertThat(outcomes).contains(ErrorCode.FEATURE_MATCH_ALREADY_RUNNING);
            assertThat(runRepository.existsById(previous)).isTrue();
            assertThat(runRepository.count()).isEqualTo(2);
            assertThat(targetRepository.count()).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("동시 대상 완료에도 실행은 최종 완료된다")
    void concurrentCompletionsFinishTheRun() throws Exception {
        long id = start();
        var targets = claimTargets("complete", 4);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var futures = targets.stream().map(target -> pool.submit(() -> {
                barrier.await();
                writer.complete(target.id(), "complete", new FeatureMatchingResult(List.of()));
                return true;
            })).toList();
            for (var future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS)).isTrue();
            }
        }
        assertThat(runs.status(id, user.getId()).status()).isEqualTo(FeatureMatchRunStatus.COMPLETED);
        assertThat(matches.counts(id).completedCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = FeatureMatchFailureCode.class, names = {
            "INPUT_TOO_LARGE", "AI_RESPONSE_INVALID", "AI_CALL_FAILED", "MAX_ATTEMPTS_EXCEEDED"
    })
    @DisplayName("일부 완료 실행은 실제 대상 실패 코드를 반환한다")
    void reportsActualFailureCodeForPartialCompletion(FeatureMatchFailureCode code) {
        long id = start();
        var targets = claimTargets("failure", 4);
        writer.complete(targets.getFirst().id(), "failure", new FeatureMatchingResult(List.of()));
        writer.fail(targets.getLast().id(), "failure", code, false);

        var status = runs.status(id, user.getId());
        assertThat(status.status()).isEqualTo(FeatureMatchRunStatus.PARTIALLY_COMPLETED);
        assertThat(status.failureCode()).isEqualTo(code);
    }

    @Test
    @DisplayName("최빈 실패 원인을 실행 대표 코드로 사용한다")
    void mostFrequentFailureBecomesRunFailure() {
        pr(3, true);
        long id = start();
        var targets = claimTargets("mixed", 4);
        writer.fail(targets.get(0).id(), "mixed", FeatureMatchFailureCode.AI_CALL_FAILED, false);
        writer.fail(targets.get(1).id(), "mixed", FeatureMatchFailureCode.INPUT_TOO_LARGE, false);
        writer.fail(targets.get(2).id(), "mixed", FeatureMatchFailureCode.INPUT_TOO_LARGE, false);

        var status = runs.status(id, user.getId());
        assertThat(status.status()).isEqualTo(FeatureMatchRunStatus.FAILED);
        assertThat(status.failureCode()).isEqualTo(FeatureMatchFailureCode.INPUT_TOO_LARGE);
    }

    @Test
    @DisplayName("실패 빈도 동률은 완료 순서와 무관하게 코드명 순서로 결정한다")
    void tiedFailureCountsUseCodeNameOrderRegardlessOfCompletionOrder() {
        long id = start();
        var targets = claimTargets("tie", 4);
        writer.fail(targets.getLast().id(), "tie", FeatureMatchFailureCode.INPUT_TOO_LARGE, false);
        writer.fail(targets.getFirst().id(), "tie", FeatureMatchFailureCode.AI_RESPONSE_INVALID, false);
        assertThat(runs.status(id, user.getId()).failureCode()).isEqualTo(FeatureMatchFailureCode.AI_RESPONSE_INVALID);
    }

    @Test
    @DisplayName("대상별 lease 갱신과 반환은 다른 선점을 변경하지 않는다")
    void perTargetHeartbeatAndReleaseCannotTouchAnotherClaim() {
        start();
        var firstTarget = claimTargets("first", 1).getFirst();
        var secondTarget = claimTargets("second", 1).getFirst();
        assertThat(claimer.heartbeat(firstTarget.id(), "second")).isFalse();
        assertThat(claimer.heartbeat(firstTarget.id(), "first")).isTrue();
        claimer.release(firstTarget.id(), "second");
        assertThat(matches.target(firstTarget.id()).status()).isEqualTo(FeatureMatchTargetStatus.RUNNING);
        claimer.release(firstTarget.id(), "first");
        assertThat(matches.target(firstTarget.id()).status()).isEqualTo(FeatureMatchTargetStatus.PENDING);
        assertThat(matches.target(firstTarget.id()).attempts()).isZero();
        assertThat(matches.target(secondTarget.id()).claimedBy()).isEqualTo("second");
    }

    @RepeatedTest(10)
    @DisplayName("동시 선점은 서로 다른 대상을 가져간다")
    void simultaneousClaimsAreDisjoint() throws Exception {
        start();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var a = pool.submit(() -> {
                barrier.await();
                return claimTargets("a", 4);
            });
            var b = pool.submit(() -> {
                barrier.await();
                return claimTargets("b", 4);
            });
            var aa = a.get(10, TimeUnit.SECONDS);
            var bb = b.get(10, TimeUnit.SECONDS);
            assertThat(aa.size() + bb.size()).isEqualTo(2);
            // 한 워커가 전부 선점하고 다른 워커는 빈 목록을 받는 것도 정상이다.
            // doesNotContainAnyElementsOf는 비교 대상이 빈 목록이면 예외를 던진다.
            var firstIds = aa.stream().map(t -> t.id()).toList();
            var secondIds = bb.stream().map(t -> t.id()).toList();
            assertThat(Collections.disjoint(firstIds, secondIds)).isTrue();
            Set<Long> claimedIds = new HashSet<>(firstIds);
            claimedIds.addAll(secondIds);
            assertThat(claimedIds).containsExactlyInAnyOrderElementsOf(
                    matches.targets(matches.latest(project.getId()).id()).stream().map(t -> t.id()).toList());
        }
    }

    @Test
    @DisplayName("복구 조회는 대기·실행 대상이 없는 활성 실행만 선택한다")
    void reconcileSelectsOnlyOrphanedRun() {
        long runId = start();
        assertThat(matches.finalizableRunIds(Limit.of(50))).isEmpty();
        jdbc.update("DELETE FROM feature_match_targets WHERE feature_match_run_id = ?", runId);
        assertThat(matches.finalizableRunIds(Limit.of(50)))
                .containsExactly(runId);
        writer.reconcile(runId);
        assertThat(runs.status(runId, user.getId()).status()).isEqualTo(FeatureMatchRunStatus.CANCELLED);
        assertThat(runs.status(runId, user.getId()).failureCode()).isEqualTo(FeatureMatchFailureCode.SOURCE_CHANGED);
        assertThat(matches.finalizableRunIds(Limit.of(50))).isEmpty();
    }

    @Test
    @DisplayName("복구 집계는 큐 처리가 끝났지만 활성으로 남은 실행만 종료한다")
    void reconcileAggregatesFinishedTargets() {
        long runId = start();
        jdbc.update("UPDATE feature_match_targets SET status = 'COMPLETED' WHERE feature_match_run_id = ?", runId);
        writer.reconcile(runId);
        assertThat(runs.status(runId, user.getId()).status()).isEqualTo(FeatureMatchRunStatus.COMPLETED);
    }

    @Test
    @DisplayName("사용자 연결 요청의 중복 ID는 입력 오류이며 아무것도 저장하지 않는다")
    void rejectsDuplicatedInputIdsAsBadRequest() {
        finish(start());
        long before = matchRepository.count();
        error(() -> manual.create(second.getId(), user.getId(),
                new FeaturePrMatchesCreateRequest(List.of(pr2.getId(), pr2.getId()))), ErrorCode.INVALID_INPUT_VALUE);
        assertThat(matchRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("미매칭 목록의 페이지 경계와 리터럴 검색을 DB에서 처리한다")
    void unmatchedDatabasePagination() {
        long runId = start();
        for (TargetRow target : claimTargets("empty", 4)) {
            writer.prepare(target.id(), "empty");
            writer.complete(target.id(), "empty", new FeatureMatchingResult(List.of()));
        }
        assertThat(queries.unmatched(project.getId(), user.getId(), null, null, 0, 1).totalElements()).isEqualTo(2);
        assertThat(queries.unmatched(project.getId(), user.getId(), null, null, 0, 1).totalPages()).isEqualTo(2);
        assertThat(queries.unmatched(project.getId(), user.getId(), null, null, 1, 1).pullRequests()).hasSize(1);
        assertThat(queries.unmatched(project.getId(), user.getId(), null, null, 2, 1).pullRequests()).isEmpty();
        assertThat(queries.unmatched(project.getId(), user.getId(), null, "%", 0, 10).pullRequests()).isEmpty();
        assertThat(runs.status(runId, user.getId()).status()).isEqualTo(FeatureMatchRunStatus.COMPLETED);
    }

    @Test
    @DisplayName("재업로드는 프로젝트 잠금을 기다리는 동안 문서를 먼저 잠그지 않는다")
    void replacementUsesProjectThenDocumentLockOrder() throws Exception {
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            AtomicReference<Future<Long>> replacement =
                    new AtomicReference<>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                matches.lockProject(project.getId(), user.getId());
                replacement.set(pool.submit(() -> new TransactionTemplate(transactionManager).execute(ignored -> {
                    jdbc.execute("SET LOCAL application_name = 'feature_match_replace_lock_test'");
                    jdbc.execute("SET LOCAL lock_timeout = '5s'");
                    return documentWriter.replace(project.getId(), user.getId(), document.getId(), "next.pdf").getId();
                })));
                // 테스트 풀은 커넥션 2개다. 조건을 별도 스레드에서 평가하면 세 번째 커넥션을 기다리다 끝난다.
                Awaitility.await().atMost(Duration.ofSeconds(3)).pollInSameThread().until(() -> {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    return jdbc.queryForObject("""
                                SELECT count(*) FROM pg_stat_activity
                                WHERE application_name = 'feature_match_replace_lock_test'
                                  AND wait_event_type = 'Lock'
                                """, Integer.class) == 1;
                });
                // 재업로드가 document를 먼저 DELETE했다면 NOWAIT는 즉시 실패한다.
                assertThat(jdbc.queryForObject("SELECT id FROM spec_documents WHERE id = ? FOR UPDATE NOWAIT",
                        Long.class, document.getId())).isEqualTo(document.getId());
            });
            Long nextDocumentId = replacement.get().get(10, TimeUnit.SECONDS);
            assertThat(documents.existsById(document.getId())).isFalse();
            assertThat(documents.existsById(nextDocumentId)).isTrue();
        }
    }

    private List<TargetRow> claimTargets(String token, int limit) {
        List<TargetRow> rows = new ArrayList<>();
        for (int index = 0; index < limit; index++) {
            Optional<Long> id = claimer.claim(token);
            if (id.isEmpty()) {
                break;
            }
            rows.add(matches.target(id.get()));
        }
        return rows;
    }
}
