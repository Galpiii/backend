package com.github.galpiii.galpi.domain.pullrequest.service;

import com.github.galpiii.galpi.domain.collection.entity.Contributor;
import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import com.github.galpiii.galpi.domain.collection.repository.ContributorRepository;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestCommitRepository;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestFileRepository;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestRepository;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.pullrequest.PullRequestFixture;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestDetailResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestListItemResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestListResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestOverviewResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestSort;
import com.github.galpiii.galpi.domain.pullrequest.entity.ChangeType;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysis;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.domain.pullrequest.entity.SummaryFailureCode;
import com.github.galpiii.galpi.domain.pullrequest.repository.PullRequestAnalysisRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR 목록·집계·상세 조회.
 *
 * <p>실제 Postgres에 붙이는 이유가 둘이다. 하나는 필터 조합이 JPQL로 그대로 나가는지 -- 선택형
 * 조건을 널 파라미터 대신 값으로 표현했기 때문에, 그 표현이 실제로 원하는 SQL이 되는지는
 * DB에 물어야만 안다. 다른 하나는 N+1이다.
 */
@DisplayName("PR 조회 — 실제 Postgres")
class PullRequestQueryIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private PullRequestQueryService queryService;
    @Autowired
    private PullRequestAnalysisRepository analysisRepository;
    @Autowired
    private PullRequestRepository pullRequestRepository;
    @Autowired
    private PullRequestFileRepository fileRepository;
    @Autowired
    private PullRequestCommitRepository commitRepository;
    @Autowired
    private ContributorRepository contributorRepository;
    @Autowired
    private GithubRepositoryRepository repositoryRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private User owner;
    private User stranger;
    private Project project;
    private GithubRepository backend;
    private GithubRepository frontend;
    private Contributor developerA;
    private Contributor developerB;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(PullRequestFixture.user("wb"));
        stranger = userRepository.save(PullRequestFixture.user("someone-else"));
        project = projectRepository.save(Project.create(owner, "갈피"));
        backend = repositoryRepository.save(
                PullRequestFixture.repository(project, "sample-org/backend"));
        frontend = repositoryRepository.save(
                PullRequestFixture.repository(project, "sample-org/frontend"));
        developerA = contributorRepository.save(
                PullRequestFixture.contributor(project, "developerA"));
        developerB = contributorRepository.save(
                PullRequestFixture.contributor(project, "developerB"));
    }

    @Nested
    @DisplayName("목록")
    class ListQuery {

        @Test
        @DisplayName("기본은 최신순이고 저장소를 가리지 않는다")
        void listsAcrossRepositories() {
            save(backend, developerA, 41, "feat: 회원가입");
            save(frontend, developerB, 12, "feat: 다크 모드");

            PullRequestListResponse response = list(null, null, null, null, null, null);

            assertThat(response.pullRequests()).extracting(PullRequestListItemResponse::number)
                    .containsExactly(41, 12);
            assertThat(response.totalElements()).isEqualTo(2);
        }

        @Test
        @DisplayName("state는 항상 MERGED이고 excluded는 항상 false다")
        void reportsFixedFields() {
            save(backend, developerA, 41, "feat: 회원가입");

            PullRequestListItemResponse item = list(null, null, null, null, null, null)
                    .pullRequests().get(0);

            assertThat(item.state()).isEqualTo("MERGED");
            assertThat(item.excluded()).isFalse();
        }

        @Test
        @DisplayName("요약 본문은 목록에 담지 않는다")
        void omitsSummaryBody() {
            PullRequest pullRequest = save(backend, developerA, 41, "feat: 회원가입");
            complete(pullRequest, "이 PR은 회원가입을 추가했습니다.");

            PullRequestListItemResponse item = list(null, null, null, null, null, null)
                    .pullRequests().get(0);

            assertThat(item.analysis().summary()).isNull();
            assertThat(item.analysis().changeType()).isEqualTo(ChangeType.FEATURE);
            assertThat(item.analysis().status()).isEqualTo(PullRequestAnalysisStatus.COMPLETED);
        }

        @Test
        @DisplayName("저장소로 좁힌다")
        void filtersByRepository() {
            save(backend, developerA, 41, "feat: 회원가입");
            save(frontend, developerB, 12, "feat: 다크 모드");

            PullRequestListResponse response =
                    list(backend.getId(), null, null, null, null, null);

            assertThat(response.pullRequests()).extracting(PullRequestListItemResponse::number)
                    .containsExactly(41);
        }

        @Test
        @DisplayName("이 프로젝트의 저장소가 아니면 PROJECT-002다")
        void rejectsForeignRepository() {
            Project other = projectRepository.save(Project.create(stranger, "남의 프로젝트"));
            GithubRepository foreign = repositoryRepository.save(
                    PullRequestFixture.repository(other, "other-org/secret"));

            assertThatThrownBy(() -> list(foreign.getId(), null, null, null, null, null))
                    .isInstanceOf(NotFoundException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.PROJECT_REPOSITORY_NOT_FOUND);
        }

        @Test
        @DisplayName("작성자로 좁힌다 — 대소문자를 가리지 않는다")
        void filtersByAuthor() {
            save(backend, developerA, 41, "feat: 회원가입");
            save(backend, developerB, 42, "fix: 오타");

            assertThat(list(null, "DEVELOPERA", null, null, null, null).pullRequests())
                    .extracting(PullRequestListItemResponse::number).containsExactly(41);
        }

        @Test
        @DisplayName("숫자 검색은 PR 번호 정확 일치나 제목 부분 일치를 본다")
        void searchesByNumber() {
            save(backend, developerA, 41, "feat: 회원가입");
            save(backend, developerB, 412, "fix: 오타");

            assertThat(list(null, null, "41", null, null, null).pullRequests())
                    .extracting(PullRequestListItemResponse::number).containsExactly(41);
        }

        @Test
        @DisplayName("숫자 검색은 작성자를 보지 않는다")
        void numericSearchIgnoresAuthor() {
            Contributor dev12 = contributorRepository.save(
                    PullRequestFixture.contributor(project, "dev12"));
            save(backend, dev12, 41, "feat: 회원가입");

            assertThat(list(null, null, "12", null, null, null).pullRequests()).isEmpty();
        }

        @Test
        @DisplayName("문자 검색은 제목과 작성자를 함께 본다")
        void searchesByTitleAndAuthor() {
            save(backend, developerA, 41, "feat: 회원가입");
            save(backend, developerB, 42, "fix: 오타");

            assertThat(list(null, null, "회원", null, null, null).pullRequests())
                    .extracting(PullRequestListItemResponse::number).containsExactly(41);
            assertThat(list(null, null, "developerb", null, null, null).pullRequests())
                    .extracting(PullRequestListItemResponse::number).containsExactly(42);
        }

        @Test
        @DisplayName("분석 상태로 좁히면 요약 행이 없는 PR은 빠진다")
        void filtersByAnalysisStatus() {
            PullRequest analyzed = save(backend, developerA, 41, "feat: 회원가입");
            complete(analyzed, "요약");
            save(backend, developerB, 42, "fix: 오타");

            assertThat(list(null, null, null, PullRequestAnalysisStatus.COMPLETED, null, null)
                    .pullRequests()).extracting(PullRequestListItemResponse::number)
                    .containsExactly(41);
        }

        @Test
        @DisplayName("분석 상태를 주지 않으면 요약 행이 없는 PR도 함께 나온다")
        void includesUnqueuedWithoutStatusFilter() {
            save(backend, developerA, 41, "feat: 회원가입");

            PullRequestListItemResponse item = list(null, null, null, null, null, null)
                    .pullRequests().get(0);

            assertThat(item.analysis()).isNull();
        }

        @Test
        @DisplayName("필터를 겹쳐 걸 수 있다")
        void combinesFilters() {
            PullRequest target = save(backend, developerA, 41, "feat: 회원가입");
            complete(target, "요약");
            complete(save(backend, developerB, 42, "feat: 회원 탈퇴"), "요약");
            save(frontend, developerA, 12, "feat: 회원 화면");

            PullRequestListResponse response = list(backend.getId(), "developera", "회원",
                    PullRequestAnalysisStatus.COMPLETED, null, null);

            assertThat(response.pullRequests()).extracting(PullRequestListItemResponse::number)
                    .containsExactly(41);
        }

        @Test
        @DisplayName("정렬을 바꿀 수 있다")
        void sortsByNumber() {
            save(backend, developerA, 41, "feat: 회원가입");
            save(frontend, developerB, 12, "feat: 다크 모드");

            assertThat(list(null, null, null, null, PullRequestSort.NUMBER_ASC, null)
                    .pullRequests()).extracting(PullRequestListItemResponse::number)
                    .containsExactly(12, 41);
        }

        @Test
        @DisplayName("페이지 경계에서 같은 PR이 두 번 나오거나 빠지지 않는다")
        void paginatesWithoutOverlap() {
            for (int number = 1; number <= 5; number++) {
                save(backend, developerA, number, "feat: " + number);
            }

            PullRequestListResponse first = queryService.list(owner.getId(), project.getId(),
                    null, null, null, null, null, 0, 2);
            PullRequestListResponse second = queryService.list(owner.getId(), project.getId(),
                    null, null, null, null, null, 1, 2);
            PullRequestListResponse third = queryService.list(owner.getId(), project.getId(),
                    null, null, null, null, null, 2, 2);

            assertThat(first.totalElements()).isEqualTo(5);
            assertThat(first.totalPages()).isEqualTo(3);
            assertThat(third.pullRequests()).hasSize(1);
            assertThat(java.util.stream.Stream.of(first, second, third)
                    .flatMap(page -> page.pullRequests().stream())
                    .map(PullRequestListItemResponse::number).toList())
                    .containsExactly(5, 4, 3, 2, 1);
        }

        @Test
        @DisplayName("size 상한을 넘겨도 상한까지만 준다")
        void capsPageSize() {
            save(backend, developerA, 41, "feat: 회원가입");

            assertThat(queryService.list(owner.getId(), project.getId(), null, null, null, null,
                    null, 0, 10_000).size()).isEqualTo(100);
        }

        @Test
        @DisplayName("남의 프로젝트는 404다")
        void rejectsForeignProject() {
            assertThatThrownBy(() -> queryService.list(stranger.getId(), project.getId(),
                    null, null, null, null, null, null, null))
                    .isInstanceOf(NotFoundException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);
        }

        @Test
        @DisplayName("연결을 끊은 저장소의 PR은 나오지 않는다")
        void hidesUnlinkedRepository() {
            save(backend, developerA, 41, "feat: 회원가입");
            backend.unlink();
            repositoryRepository.saveAndFlush(backend);

            assertThat(list(null, null, null, null, null, null).pullRequests()).isEmpty();
        }

        @Test
        @DisplayName("PR이 몇 건이든 쿼리 수가 늘지 않는다")
        void doesNotTriggerNPlusOne() {
            complete(save(backend, developerA, 1, "feat: 1"), "요약 1");
            long withOne = countStatements(() -> list(null, null, null, null, null, 20));

            for (int number = 2; number <= 10; number++) {
                complete(save(backend, number % 2 == 0 ? developerA : developerB,
                        number, "feat: " + number), "요약 " + number);
            }
            long withTen = countStatements(() -> list(null, null, null, null, null, 20));

            assertThat(list(null, null, null, null, null, 20).pullRequests()).hasSize(10);
            // 항목마다 저장소·작성자·요약을 다시 읽으면 건수에 비례해 늘어난다.
            assertThat(withTen).isEqualTo(withOne);
        }
    }

    @Nested
    @DisplayName("헤더 집계")
    class Overview {

        @Test
        @DisplayName("저장소별 숫자와 프로젝트 전체 숫자를 함께 준다")
        void aggregatesCounts() {
            complete(save(backend, developerA, 41, "feat: 회원가입"), "요약");
            fail(save(backend, developerB, 42, "fix: 오타"));
            save(frontend, developerA, 12, "feat: 다크 모드");

            PullRequestOverviewResponse overview =
                    queryService.overview(owner.getId(), project.getId());

            assertThat(overview.totalCount()).isEqualTo(3);
            assertThat(overview.failedCount()).isEqualTo(1);
            assertThat(overview.pendingCount()).isZero();
            assertThat(overview.excludedCount()).isZero();
            assertThat(overview.lastAnalyzedAt()).isNotNull();
            assertThat(overview.repositories()).hasSize(2)
                    .anySatisfy(row -> {
                        assertThat(row.fullName()).isEqualTo("sample-org/backend");
                        assertThat(row.pullRequestCount()).isEqualTo(2);
                        assertThat(row.failedCount()).isEqualTo(1);
                    });
        }

        @Test
        @DisplayName("모든 분석이 실패했어도 마지막 분석 시각을 준다")
        void reportsLastAnalyzedAtWhenAllAnalysesFailed() {
            fail(save(backend, developerA, 41, "fix: 실패하는 변경"));

            PullRequestOverviewResponse overview =
                    queryService.overview(owner.getId(), project.getId());

            assertThat(overview.failedCount()).isEqualTo(1);
            assertThat(overview.lastAnalyzedAt()).isNotNull();
        }

        @Test
        @DisplayName("PR이 하나도 없는 저장소도 0으로 나온다")
        void includesEmptyRepository() {
            PullRequestOverviewResponse overview =
                    queryService.overview(owner.getId(), project.getId());

            assertThat(overview.repositories()).hasSize(2)
                    .allSatisfy(row -> assertThat(row.pullRequestCount()).isZero());
            assertThat(overview.totalCount()).isZero();
        }

        @Test
        @DisplayName("수집 기준은 아직 고정값이다")
        void reportsFixedCriteria() {
            PullRequestOverviewResponse overview =
                    queryService.overview(owner.getId(), project.getId());

            assertThat(overview.criteria().state()).isEqualTo("MERGED");
            assertThat(overview.criteria().baseBranch()).isEqualTo("DEFAULT");
            assertThat(overview.criteria().period()).isEqualTo("ALL");
        }

        @Test
        @DisplayName("집계는 쿼리 한 번으로 끝낸다")
        void usesSingleQuery() {
            for (int number = 1; number <= 5; number++) {
                complete(save(backend, developerA, number, "feat: " + number), "요약");
            }

            Statistics statistics = statistics();
            statistics.clear();
            queryService.overview(owner.getId(), project.getId());

            // 프로젝트 소유권 확인 한 번 + 집계 한 번. 저장소마다 count를 돌면 여기서 걸린다.
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("남의 프로젝트는 404다")
        void rejectsForeignProject() {
            assertThatThrownBy(() -> queryService.overview(stranger.getId(), project.getId()))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("상세")
    class Detail {

        @Test
        @DisplayName("GitHub 원본과 AI 분석을 함께 준다")
        void returnsBothCards() {
            PullRequest pullRequest = save(backend, developerA, 41, "feat: 회원가입");
            fileRepository.save(PullRequestFixture.file(pullRequest, "src/AuthController.java"));
            commitRepository.save(
                    PullRequestFixture.commit(pullRequest, "a3f9c21", "feat: 회원가입 API 구현"));
            complete(pullRequest, "회원가입 처리를 추가했습니다.");

            PullRequestDetailResponse detail =
                    queryService.detail(owner.getId(), pullRequest.getId());

            assertThat(detail.number()).isEqualTo(41);
            assertThat(detail.state()).isEqualTo("MERGED");
            assertThat(detail.baseRef()).isEqualTo("develop");
            assertThat(detail.author().login()).isEqualTo("developerA");
            assertThat(detail.repository().fullName()).isEqualTo("sample-org/backend");
            assertThat(detail.files()).hasSize(1);
            assertThat(detail.filesTruncated()).isFalse();
            assertThat(detail.commits()).hasSize(1);
            assertThat(detail.commitsTruncated()).isFalse();
            assertThat(detail.analysis().summary()).isEqualTo("회원가입 처리를 추가했습니다.");
            assertThat(detail.analysis().changeType()).isEqualTo(ChangeType.FEATURE);
        }

        @Test
        @DisplayName("작성자가 없는 PR은 author를 null로 준다")
        void handlesMissingAuthor() {
            PullRequest pullRequest = save(backend, null, 41, "feat: 회원가입");

            assertThat(queryService.detail(owner.getId(), pullRequest.getId()).author()).isNull();
        }

        @Test
        @DisplayName("커밋이 응답 상한을 넘으면 정렬된 앞 200개만 주고 잘림을 알린다")
        void capsCommitsAndReportsTruncation() {
            PullRequest pullRequest = save(backend, developerA, 41, "feat: 큰 PR");
            commitRepository.saveAllAndFlush(IntStream.rangeClosed(1, 201)
                    .mapToObj(number -> PullRequestFixture.commit(
                            pullRequest, "sha" + number, "commit " + number))
                    .toList());

            PullRequestDetailResponse detail =
                    queryService.detail(owner.getId(), pullRequest.getId());

            assertThat(detail.commits()).hasSize(200);
            assertThat(detail.commitsTruncated()).isTrue();
            assertThat(detail.commits().getFirst().sha()).isEqualTo("sha1");
            assertThat(detail.commits().getLast().sha()).isEqualTo("sha200");
        }

        @Test
        @DisplayName("요약이 실패했으면 summary는 비고 errorCode가 채워진다")
        void reportsFailedAnalysis() {
            PullRequest pullRequest = save(backend, developerA, 41, "feat: 회원가입");
            fail(pullRequest);

            PullRequestDetailResponse detail =
                    queryService.detail(owner.getId(), pullRequest.getId());

            assertThat(detail.analysis().status()).isEqualTo(PullRequestAnalysisStatus.FAILED);
            assertThat(detail.analysis().summary()).isNull();
            assertThat(detail.analysis().errorCode())
                    .isEqualTo(SummaryFailureCode.SUMMARY_LLM_FAILED);
        }

        @Test
        @DisplayName("아직 인계되지 않은 PR은 analysis가 null이다")
        void reportsMissingAnalysis() {
            PullRequest pullRequest = save(backend, developerA, 41, "feat: 회원가입");

            assertThat(queryService.detail(owner.getId(), pullRequest.getId()).analysis()).isNull();
        }

        @Test
        @DisplayName("남의 PR은 404다")
        void rejectsForeignPullRequest() {
            PullRequest pullRequest = save(backend, developerA, 41, "feat: 회원가입");

            assertThatThrownBy(() -> queryService.detail(stranger.getId(), pullRequest.getId()))
                    .isInstanceOf(NotFoundException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PULL_REQUEST_NOT_FOUND);
        }

        @Test
        @DisplayName("없는 PR도 404다")
        void rejectsMissingPullRequest() {
            assertThatThrownBy(() -> queryService.detail(owner.getId(), 999_999L))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    private PullRequestListResponse list(Long repositoryId, String authorLogin, String query,
                                         PullRequestAnalysisStatus status, PullRequestSort sort,
                                         Integer size) {
        return queryService.list(owner.getId(), project.getId(), repositoryId, authorLogin,
                query, status, sort, null, size);
    }

    private PullRequest save(GithubRepository repository, Contributor contributor, int number,
                             String title) {
        return pullRequestRepository.saveAndFlush(
                PullRequestFixture.pullRequest(repository, contributor, number, title));
    }

    private void complete(PullRequest pullRequest, String summary) {
        PullRequestAnalysis analysis = PullRequestAnalysis.pending(
                pullRequest, 5000L, owner, pullRequest.getHeadSha());
        analysis.complete(summary, ChangeType.FEATURE, "gpt-5-mini");
        analysisRepository.saveAndFlush(analysis);
    }

    private void fail(PullRequest pullRequest) {
        PullRequestAnalysis analysis = PullRequestAnalysis.pending(
                pullRequest, 5000L, owner, pullRequest.getHeadSha());
        analysis.fail(SummaryFailureCode.SUMMARY_LLM_FAILED, "실패");
        analysisRepository.saveAndFlush(analysis);
    }

    /** 한 번의 호출이 실제로 보낸 SQL 문 수. */
    private long countStatements(Runnable action) {
        Statistics statistics = statistics();
        statistics.clear();
        action.run();
        return statistics.getPrepareStatementCount();
    }

    /**
     * Hibernate 통계.
     *
     * <p>속성으로 켜지 않고 런타임에 켠다. 속성을 바꾸면 컨텍스트 캐시 키가 달라져 이 테스트만
     * 스프링 컨텍스트를 새로 띄운다.
     */
    private Statistics statistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }
}
