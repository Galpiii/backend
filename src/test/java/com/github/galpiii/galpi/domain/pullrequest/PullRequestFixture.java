package com.github.galpiii.galpi.domain.pullrequest;

import com.github.galpiii.galpi.domain.collection.entity.ChangeStatus;
import com.github.galpiii.galpi.domain.collection.entity.Contributor;
import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import com.github.galpiii.galpi.domain.collection.entity.PullRequestCommit;
import com.github.galpiii.galpi.domain.collection.entity.PullRequestFile;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.user.entity.User;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PR 관련 통합 테스트가 함께 쓰는 고정값.
 *
 * <p>PR 하나를 만들려면 사용자·프로젝트·저장소·기여자를 먼저 만들어야 하고, 그 사슬을 테스트
 * 클래스마다 다시 쓰면 한 엔티티에 필수 컬럼이 늘 때 여러 곳이 함께 깨진다.
 */
public final class PullRequestFixture {

    /** GitHub id는 UNIQUE라 테스트 사이에 겹치면 안 된다. */
    private static final AtomicLong SEQUENCE = new AtomicLong(1);

    private PullRequestFixture() {
    }

    public static User user(String login) {
        return User.ofGithub(SEQUENCE.incrementAndGet(), login, login, null, "https://avatar");
    }

    public static GithubRepository repository(Project project, String fullName) {
        long githubId = SEQUENCE.incrementAndGet();
        String[] parts = fullName.split("/");
        return GithubRepository.link(project, new RepositorySnapshot(
                githubId, 5000L, parts[0], parts[1], fullName, true, "main",
                "https://github.com/" + fullName));
    }

    public static Contributor contributor(Project project, String login) {
        return Contributor.of(project, SEQUENCE.incrementAndGet(), login,
                "https://avatar/" + login);
    }

    /**
     * 병합된 PR 하나.
     *
     * <p>{@code mergedAt}을 번호에서 만들어 낸다. 번호가 큰 PR이 최신이어야 정렬 테스트가
     * 사람 직관과 맞는다.
     */
    public static PullRequest pullRequest(GithubRepository repository, Contributor contributor,
                                          int number, String title) {
        return pullRequest(repository, contributor, number, title, "head" + number);
    }

    public static PullRequest pullRequest(GithubRepository repository, Contributor contributor,
                                          int number, String title, String headSha) {
        OffsetDateTime mergedAt = OffsetDateTime.now().minusHours(500 - number);
        return PullRequest.builder()
                .repository(repository)
                .contributor(contributor)
                .githubPullRequestId(SEQUENCE.incrementAndGet())
                .number(number)
                .title(title)
                .body("본문 " + number)
                .baseRef("develop")
                .headRef("feature/" + number)
                .baseSha("base" + number)
                .headSha(headSha)
                .mergeCommitSha("merge" + number)
                .mergedAt(mergedAt)
                .createdAtGithub(mergedAt.minusHours(2))
                .changedFilesCount(2)
                .additions(120)
                .deletions(8)
                .htmlUrl("https://github.com/" + repository.getFullName() + "/pull/" + number)
                .incompleteReasons(List.of())
                .build();
    }

    public static PullRequestFile file(PullRequest pullRequest, String path) {
        return PullRequestFile.of(pullRequest, path, null, ChangeStatus.ADDED, 10, 0, 10, false);
    }

    public static PullRequestCommit commit(PullRequest pullRequest, String sha, String message) {
        return PullRequestCommit.of(pullRequest, sha, message, "developerA", 1L,
                OffsetDateTime.now().minusDays(1));
    }
}
