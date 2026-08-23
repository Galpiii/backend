package com.github.galpiii.galpi.domain.collection.pr;

import com.github.galpiii.galpi.domain.collection.config.CollectionProperties;
import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import com.github.galpiii.galpi.domain.collection.pipeline.CollectedRepositorySnapshot.CollectedPullRequest;
import com.github.galpiii.galpi.domain.collection.repository.PrExclusionRepository;
import com.github.galpiii.galpi.domain.collection.secret.SecretContentScanner;
import com.github.galpiii.galpi.domain.github.client.GithubCollectionClient;
import com.github.galpiii.galpi.domain.github.client.dto.GithubCommitResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubPullRequestFileResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubPullRequestResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubUserResponse;
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
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PullRequestCollector — PR 수집 범위")
class PullRequestCollectorTest {

    private static final String TOKEN = "ghs_installationtoken";
    private static final String OWNER = "octocat";
    private static final String REPO = "hello";
    private static final long REPOSITORY_ID = 42L;
    private static final String FAKE_GITHUB_TOKEN = "ghp_1234567890abcdefGHIJKLMNOPqrstuvwx12";

    @Mock
    private GithubCollectionClient client;
    @Mock
    private PullRequestWriter writer;
    @Mock
    private PrExclusionRepository exclusionRepository;

    private PullRequestCollector collector;

    @BeforeEach
    void setUp() {
        collector = new PullRequestCollector(client, writer, exclusionRepository,
                new SecretContentScanner(), properties());
        given(exclusionRepository.findExcludedNumbersByRepositoryId(anyLong()))
                .willReturn(List.of());
        given(client.listFiles(anyString(), anyString(), anyString(), anyInt(), any()))
                .willReturn(new GithubCollectionClient.PagedResult<>(List.of(), false));
        given(client.listCommits(anyString(), anyString(), anyString(), anyInt(), any()))
                .willReturn(new GithubCollectionClient.PagedResult<>(List.of(), false));
    }

    @Nested
    @DisplayName("수집 범위")
    class Scope {

        @Test
        @DisplayName("병합된 PR만 수집하고 닫히기만 한 PR은 건너뛴다")
        void collectsMergedOnly() {
            givenListPage(merged(1), closedUnmerged(2), merged(3));
            givenDetails(1, 3);

            PullRequestCollector.PullRequestCollectionResult result = collect(300, null);

            assertThat(result.collectedCount()).isEqualTo(2);
            assertThat(result.pullRequests())
                    .extracting(CollectedPullRequest::number)
                    .containsExactly(1, 3);
            verify(client, never()).getPullRequest(eq(TOKEN), eq(OWNER), eq(REPO), eq(2), any());
        }

        @Test
        @DisplayName("상한을 넘으면 잘라내고 PR_LIMIT_EXCEEDED를 남긴다")
        void recordsLimitExceeded() {
            givenListPage(merged(1), merged(2), merged(3));
            givenDetails(1, 2);

            PullRequestCollector.PullRequestCollectionResult result = collect(2, null);

            assertThat(result.collectedCount()).isEqualTo(2);
            assertThat(result.incompleteReasons())
                    .contains(IncompleteReason.PR_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("상한에 딱 맞으면 PR_LIMIT_EXCEEDED를 남기지 않는다")
        void doesNotRecordLimitWhenExactlyAtLimit() {
            givenListPage(merged(1), merged(2));
            givenDetails(1, 2);

            PullRequestCollector.PullRequestCollectionResult result = collect(2, null);

            assertThat(result.incompleteReasons())
                    .doesNotContain(IncompleteReason.PR_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("pr_since 이전에 병합된 PR을 건너뛴다")
        void appliesPrSince() {
            GithubPullRequestResponse old = mergedAt(1, OffsetDateTime.parse("2020-01-01T00:00Z"));
            GithubPullRequestResponse recent =
                    mergedAt(2, OffsetDateTime.parse("2026-01-01T00:00Z"));
            givenListPage(old, recent);
            givenDetails(2);

            PullRequestCollector.PullRequestCollectionResult result =
                    collect(300, OffsetDateTime.parse("2025-01-01T00:00Z"));

            assertThat(result.pullRequests())
                    .extracting(CollectedPullRequest::number)
                    .containsExactly(2);
        }

        @Test
        @DisplayName("pr_exclusions에 있는 PR은 저장은 하되 파이프라인에 넘기지 않는다")
        void keepsExcludedPullRequestOutOfPipeline() {
            givenListPage(merged(1), merged(2));
            givenDetails(1, 2);
            given(exclusionRepository.findExcludedNumbersByRepositoryId(REPOSITORY_ID))
                    .willReturn(List.of(2));

            PullRequestCollector.PullRequestCollectionResult result = collect(300, null);

            assertThat(result.collectedCount()).isEqualTo(2);
            assertThat(result.pullRequests())
                    .extracting(CollectedPullRequest::number)
                    .containsExactly(1);
        }
    }

    @Nested
    @DisplayName("작성자와 마스킹")
    class AuthorAndMasking {

        @Test
        @DisplayName("삭제된 계정이 작성한 PR도 오류 없이 수집한다")
        void collectsPullRequestFromDeletedAccount() {
            givenListPage(merged(1));
            given(client.getPullRequest(eq(TOKEN), eq(OWNER), eq(REPO), eq(1), any()))
                    .willReturn(detail(1, null));

            PullRequestCollector.PullRequestCollectionResult result = collect(300, null);

            assertThat(result.collectedCount()).isEqualTo(1);
            ArgumentCaptor<PullRequestWriter.CollectedPullRequestData> captor =
                    ArgumentCaptor.forClass(PullRequestWriter.CollectedPullRequestData.class);
            verify(writer).save(eq(REPOSITORY_ID), captor.capture());
            assertThat(captor.getValue().authorGithubId()).isNull();
            assertThat(captor.getValue().authorLogin()).isNull();
        }

        @Test
        @DisplayName("PR 본문의 가짜 토큰을 마스킹해서 저장한다")
        void masksSecretInBody() {
            givenListPage(merged(1));
            given(client.getPullRequest(eq(TOKEN), eq(OWNER), eq(REPO), eq(1), any()))
                    .willReturn(detailWithBody(1, "배포 토큰: " + FAKE_GITHUB_TOKEN));

            collect(300, null);

            ArgumentCaptor<PullRequestWriter.CollectedPullRequestData> captor =
                    ArgumentCaptor.forClass(PullRequestWriter.CollectedPullRequestData.class);
            verify(writer).save(eq(REPOSITORY_ID), captor.capture());
            assertThat(captor.getValue().body())
                    .doesNotContain(FAKE_GITHUB_TOKEN)
                    .contains("***");
        }

        @Test
        @DisplayName("커밋 메시지의 가짜 토큰을 마스킹해서 저장한다")
        void masksSecretInCommitMessage() {
            givenListPage(merged(1));
            givenDetails(1);
            given(client.listCommits(eq(TOKEN), eq(OWNER), eq(REPO), eq(1), any())).willReturn(
                    new GithubCollectionClient.PagedResult<>(List.of(commit("abc123",
                            "fix: 토큰 " + FAKE_GITHUB_TOKEN + " 제거")), false));

            collect(300, null);

            ArgumentCaptor<PullRequestWriter.CollectedPullRequestData> captor =
                    ArgumentCaptor.forClass(PullRequestWriter.CollectedPullRequestData.class);
            verify(writer).save(eq(REPOSITORY_ID), captor.capture());
            assertThat(captor.getValue().commits().getFirst().message())
                    .doesNotContain(FAKE_GITHUB_TOKEN)
                    .contains("***");
        }

        @Test
        @DisplayName("PR patch의 가짜 토큰을 마스킹해서 파이프라인에 넘긴다")
        void masksSecretInPatch() {
            givenListPage(merged(1));
            givenDetails(1);
            given(client.listFiles(eq(TOKEN), eq(OWNER), eq(REPO), eq(1), any())).willReturn(
                    new GithubCollectionClient.PagedResult<>(List.of(
                            new GithubPullRequestFileResponse("deploy.sh", null, "modified",
                                    1, 0, 1, "+TOKEN=" + FAKE_GITHUB_TOKEN)), false));

            PullRequestCollector.PullRequestCollectionResult result = collect(300, null);

            CollectedPullRequest pullRequest = result.pullRequests().getFirst();
            assertThat(pullRequest.files().getFirst().patch())
                    .doesNotContain(FAKE_GITHUB_TOKEN)
                    .contains("***");
            assertThat(pullRequest.incompleteReasons())
                    .contains(IncompleteReason.SECRET_REDACTED);
        }
    }

    @Nested
    @DisplayName("diff 취급")
    class DiffHandling {

        @Test
        @DisplayName("patch는 파이프라인에만 넘기고 저장 대상에는 넣지 않는다")
        void keepsPatchOutOfPersistedData() {
            givenListPage(merged(1));
            givenDetails(1);
            given(client.listFiles(eq(TOKEN), eq(OWNER), eq(REPO), eq(1), any())).willReturn(
                    new GithubCollectionClient.PagedResult<>(List.of(new GithubPullRequestFileResponse(
                            "src/App.java", null, "modified", 3, 1, 4,
                            "@@ -1 +1 @@\n-old\n+new")), false));

            PullRequestCollector.PullRequestCollectionResult result = collect(300, null);

            assertThat(result.pullRequests().getFirst().files().getFirst().patch())
                    .isEqualTo("@@ -1 +1 @@\n-old\n+new");

            ArgumentCaptor<PullRequestWriter.CollectedPullRequestData> captor =
                    ArgumentCaptor.forClass(PullRequestWriter.CollectedPullRequestData.class);
            verify(writer).save(eq(REPOSITORY_ID), captor.capture());
            // 저장용 record에는 patch를 담을 필드 자체가 없다. 남는 것은 생략 여부뿐이다.
            assertThat(captor.getValue().files().getFirst().patchOmitted()).isFalse();
        }

        @Test
        @DisplayName("patch가 생략되면 PATCH_OMITTED를 기록한다")
        void recordsPatchOmitted() {
            givenListPage(merged(1));
            givenDetails(1);
            given(client.listFiles(eq(TOKEN), eq(OWNER), eq(REPO), eq(1), any())).willReturn(
                    new GithubCollectionClient.PagedResult<>(List.of(new GithubPullRequestFileResponse(
                            "assets/logo.png", null, "added", 0, 0, 0, null)), false));

            PullRequestCollector.PullRequestCollectionResult result = collect(300, null);

            assertThat(result.pullRequests().getFirst().incompleteReasons())
                    .contains(IncompleteReason.PATCH_OMITTED, IncompleteReason.BINARY);
        }

        @Test
        @DisplayName("변경 파일이 페이지 상한에 걸리면 FILE_LIMIT_EXCEEDED를 기록한다")
        void recordsFileLimitExceeded() {
            givenListPage(merged(1));
            givenDetails(1);
            given(client.listFiles(eq(TOKEN), eq(OWNER), eq(REPO), eq(1), any())).willReturn(
                    new GithubCollectionClient.PagedResult<>(List.of(), true));

            PullRequestCollector.PullRequestCollectionResult result = collect(300, null);

            assertThat(result.pullRequests().getFirst().incompleteReasons())
                    .contains(IncompleteReason.FILE_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("커밋 목록이 페이지 상한에 걸리면 COMMIT_LIMIT_EXCEEDED를 기록한다")
        void recordsCommitLimitExceeded() {
            givenListPage(merged(1));
            givenDetails(1);
            given(client.listCommits(eq(TOKEN), eq(OWNER), eq(REPO), eq(1), any())).willReturn(
                    new GithubCollectionClient.PagedResult<>(List.of(), true));

            PullRequestCollector.PullRequestCollectionResult result = collect(300, null);

            assertThat(result.pullRequests().getFirst().incompleteReasons())
                    .contains(IncompleteReason.COMMIT_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("파이프라인 PR 문자열 총량을 넘으면 내용을 버리고 사유를 기록한다")
        void boundsPipelineContent() {
            collector = new PullRequestCollector(client, writer, exclusionRepository,
                    new SecretContentScanner(), properties(DataSize.ofBytes(3), 900));
            givenListPage(merged(1));
            givenDetails(1);

            PullRequestCollector.PullRequestCollectionResult result = collect(300, null);

            assertThat(result.pullRequests().getFirst().title()).isEmpty();
            assertThat(result.pullRequests().getFirst().incompleteReasons())
                    .contains(IncompleteReason.PR_CONTENT_LIMIT);
        }

        @Test
        @DisplayName("저장소 PR API 요청 budget이 소진되면 나머지 수집을 중단한다")
        void boundsApiRequests() {
            givenListPage(merged(1));
            givenDetails(1);
            given(client.listCommits(eq(TOKEN), eq(OWNER), eq(REPO), eq(1), any()))
                    .willThrow(new GithubCollectionClient.RequestBudgetExceededException());

            PullRequestCollector.PullRequestCollectionResult result = collect(300, null);

            assertThat(result.collectedCount()).isZero();
            assertThat(result.incompleteReasons()).contains(IncompleteReason.PR_REQUEST_LIMIT);
        }
    }

    private PullRequestCollector.PullRequestCollectionResult collect(int prLimit,
                                                                     OffsetDateTime prSince) {
        return collector.collect(TOKEN, OWNER, REPO, REPOSITORY_ID, prLimit, prSince);
    }

    private void givenListPage(GithubPullRequestResponse... items) {
        given(client.listClosedPullRequests(eq(TOKEN), eq(OWNER), eq(REPO), any(), any()))
                .willReturn(new GithubCollectionClient.GithubPage<>(List.of(items), null));
    }

    private void givenDetails(int... numbers) {
        for (int number : numbers) {
            given(client.getPullRequest(eq(TOKEN), eq(OWNER), eq(REPO), eq(number), any()))
                    .willReturn(detail(number, user()));
        }
    }

    private static GithubPullRequestResponse merged(int number) {
        return mergedAt(number, OffsetDateTime.parse("2026-01-01T00:00Z"));
    }

    private static GithubPullRequestResponse mergedAt(int number, OffsetDateTime mergedAt) {
        return new GithubPullRequestResponse((long) (1000 + number), number, "PR " + number, null,
                "closed", false, mergedAt, mergedAt.minusDays(1), "merge" + number,
                "https://github.com/octocat/hello/pull/" + number, 1, 1, 0,
                new GithubPullRequestResponse.Ref("main", "base" + number),
                new GithubPullRequestResponse.Ref("feature", "head" + number), user());
    }

    private static GithubPullRequestResponse closedUnmerged(int number) {
        return new GithubPullRequestResponse((long) (1000 + number), number, "PR " + number, null,
                "closed", false, null, OffsetDateTime.parse("2026-01-01T00:00Z"), null,
                "https://github.com/octocat/hello/pull/" + number, 1, 1, 0,
                new GithubPullRequestResponse.Ref("main", "base"),
                new GithubPullRequestResponse.Ref("feature", "head"), user());
    }

    private static GithubPullRequestResponse detail(int number, GithubUserResponse author) {
        return new GithubPullRequestResponse((long) (1000 + number), number, "PR " + number, null,
                "closed", false, OffsetDateTime.parse("2026-01-01T00:00Z"),
                OffsetDateTime.parse("2025-12-01T00:00Z"), "merge" + number,
                "https://github.com/octocat/hello/pull/" + number, 2, 10, 3,
                new GithubPullRequestResponse.Ref("main", "base" + number),
                new GithubPullRequestResponse.Ref("feature", "head" + number), author);
    }

    private static GithubPullRequestResponse detailWithBody(int number, String body) {
        return new GithubPullRequestResponse((long) (1000 + number), number, "PR " + number, body,
                "closed", false, OffsetDateTime.parse("2026-01-01T00:00Z"),
                OffsetDateTime.parse("2025-12-01T00:00Z"), "merge" + number,
                "https://github.com/octocat/hello/pull/" + number, 2, 10, 3,
                new GithubPullRequestResponse.Ref("main", "base"),
                new GithubPullRequestResponse.Ref("feature", "head"), user());
    }

    private static GithubCommitResponse commit(String sha, String message) {
        return new GithubCommitResponse(sha,
                new GithubCommitResponse.Commit(message, new GithubCommitResponse.GitIdentity(
                        "Octo Cat", OffsetDateTime.parse("2025-12-15T00:00Z"))),
                user());
    }

    private static GithubUserResponse user() {
        return new GithubUserResponse(77L, "octocat", "https://avatars/octocat", null, "Octo Cat");
    }

    private static CollectionProperties properties() {
        return properties(DataSize.ofMegabytes(20), 900);
    }

    private static CollectionProperties properties(DataSize maxPullRequestContentSize,
                                                   int maxPullRequestApiRequests) {
        return new CollectionProperties(DataSize.ofMegabytes(200), DataSize.ofGigabytes(1),
                20_000, DataSize.ofMegabytes(1), DataSize.ofMegabytes(20),
                maxPullRequestContentSize, 3, Duration.ofSeconds(120), 30, 30, 30,
                maxPullRequestApiRequests);
    }
}
