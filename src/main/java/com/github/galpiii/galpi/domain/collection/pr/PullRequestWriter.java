package com.github.galpiii.galpi.domain.collection.pr;

import com.github.galpiii.galpi.domain.collection.entity.ChangeStatus;
import com.github.galpiii.galpi.domain.collection.entity.Contributor;
import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import com.github.galpiii.galpi.domain.collection.entity.PullRequestCommit;
import com.github.galpiii.galpi.domain.collection.entity.PullRequestFile;
import com.github.galpiii.galpi.domain.collection.repository.ContributorRepository;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestCommitRepository;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestFileRepository;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestRepository;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * PR 하나를 DB에 쓴다.
 *
 * <p>수집기와 분리한 이유는 트랜잭션 길이다. PR 하나에 GitHub 호출이 세 번 들어가는데, 전체를
 * 한 트랜잭션으로 감싸면 저장소 하나를 수집하는 몇 분 내내 DB 커넥션이 묶인다. 여기서 PR 단위로
 * 짧게 커밋한다.
 *
 * <p>중간에 실패해도 그때까지 저장된 PR은 남는다. 부분 실패를 전체 실패로 만들지 않는다는
 * 원칙과 맞고, 다시 시도하면 이미 있는 PR은 갱신된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PullRequestWriter {

    private final GithubRepositoryRepository repositoryRepository;
    private final ContributorRepository contributorRepository;
    private final PullRequestRepository pullRequestRepository;
    private final PullRequestFileRepository fileRepository;
    private final PullRequestCommitRepository commitRepository;

    /**
     * @param collected 마스킹까지 끝난 값. 이 메서드는 마스킹하지 않는다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(Long repositoryId, CollectedPullRequestData collected) {
        GithubRepository repository = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROJECT_REPOSITORY_NOT_FOUND));

        Contributor contributor = upsertContributor(repository, collected);
        PullRequest pullRequest = upsertPullRequest(repository, contributor, collected);

        replaceFiles(pullRequest, collected.files());
        replaceCommits(pullRequest, collected.commits());
    }

    /**
     * 삭제된 GitHub 계정의 PR은 작성자가 없다. 그 경우 {@code null}을 돌려주고
     * {@code contributor_id}를 비운 채 저장한다.
     */
    private Contributor upsertContributor(GithubRepository repository,
                                          CollectedPullRequestData collected) {
        Long githubUserId = collected.authorGithubId();
        if (githubUserId == null) {
            return null;
        }
        Long projectId = repository.getProject().getId();

        return contributorRepository.findByProjectIdAndGithubUserId(projectId, githubUserId)
                .map(existing -> {
                    existing.refresh(collected.authorLogin(), collected.authorAvatarUrl());
                    return existing;
                })
                .orElseGet(() -> insertContributor(repository, collected, githubUserId, projectId));
    }

    /**
     * 유니크 충돌은 정상 경로다. 같은 프로젝트를 다시 분석하거나 같은 작성자의 PR이 연달아 오면
     * 조회와 삽입 사이에 다른 트랜잭션이 먼저 넣을 수 있다. 그때는 넣은 쪽 행을 쓴다.
     */
    private Contributor insertContributor(GithubRepository repository,
                                          CollectedPullRequestData collected, Long githubUserId,
                                          Long projectId) {
        try {
            return contributorRepository.save(Contributor.of(repository.getProject(), githubUserId,
                    collected.authorLogin(), collected.authorAvatarUrl()));
        } catch (DataIntegrityViolationException e) {
            return contributorRepository.findByProjectIdAndGithubUserId(projectId, githubUserId)
                    .orElseThrow(() -> e);
        }
    }

    private PullRequest upsertPullRequest(GithubRepository repository, Contributor contributor,
                                          CollectedPullRequestData collected) {
        PullRequest built = PullRequest.builder()
                .repository(repository)
                .contributor(contributor)
                .githubPullRequestId(collected.githubPullRequestId())
                .number(collected.number())
                .title(collected.title())
                .body(collected.body())
                .baseRef(collected.baseRef())
                .headRef(collected.headRef())
                .baseSha(collected.baseSha())
                .headSha(collected.headSha())
                .mergeCommitSha(collected.mergeCommitSha())
                .mergedAt(collected.mergedAt())
                .createdAtGithub(collected.createdAtGithub())
                .changedFilesCount(collected.changedFilesCount())
                .additions(collected.additions())
                .deletions(collected.deletions())
                .htmlUrl(collected.htmlUrl())
                .incompleteReasons(collected.incompleteReasons())
                .build();

        return pullRequestRepository
                .findByRepositoryIdAndNumber(repository.getId(), collected.number())
                .map(existing -> {
                    existing.refresh(built);
                    return existing;
                })
                .orElseGet(() -> pullRequestRepository.save(built));
    }

    /**
     * 기존 행을 지우고 새로 넣는다.
     *
     * <p>경로 단위로 대조해 갱신하면 지난번에 있다가 이번에 사라진 파일이 남는다. 다시 수집한
     * 결과가 곧 현재 상태이므로 통째로 갈아 끼우는 편이 정확하다.
     */
    private void replaceFiles(PullRequest pullRequest, List<CollectedFileData> files) {
        fileRepository.deleteAllByPullRequestId(pullRequest.getId());
        fileRepository.flush();

        // patch는 여기 오지 않는다. 이 메서드가 다루는 값에 diff 원문이 없다는 것이 중요하다.
        List<PullRequestFile> entities = files.stream()
                .map(file -> PullRequestFile.of(pullRequest, file.path(), file.previousPath(),
                        file.changeStatus(), file.additions(), file.deletions(), file.changes(),
                        file.patchOmitted()))
                .toList();
        fileRepository.saveAll(entities);
    }

    private void replaceCommits(PullRequest pullRequest, List<CollectedCommitData> commits) {
        commitRepository.deleteAllByPullRequestId(pullRequest.getId());
        commitRepository.flush();

        List<PullRequestCommit> entities = commits.stream()
                .map(commit -> PullRequestCommit.of(pullRequest, commit.sha(), commit.message(),
                        commit.authorLogin(), commit.authorGithubId(), commit.authoredAt()))
                .toList();
        commitRepository.saveAll(entities);
    }

    /** 저장에 필요한 값만 담은 전달 객체. diff 원문은 들어 있지 않다. */
    public record CollectedPullRequestData(Long githubPullRequestId,
                                           Integer number,
                                           String title,
                                           String body,
                                           String baseRef,
                                           String headRef,
                                           String baseSha,
                                           String headSha,
                                           String mergeCommitSha,
                                           OffsetDateTime mergedAt,
                                           OffsetDateTime createdAtGithub,
                                           int changedFilesCount,
                                           int additions,
                                           int deletions,
                                           String htmlUrl,
                                           Long authorGithubId,
                                           String authorLogin,
                                           String authorAvatarUrl,
                                           List<IncompleteReason> incompleteReasons,
                                           List<CollectedFileData> files,
                                           List<CollectedCommitData> commits) {
    }

    public record CollectedFileData(String path, String previousPath, ChangeStatus changeStatus,
                                    int additions, int deletions, int changes,
                                    boolean patchOmitted) {
    }

    public record CollectedCommitData(String sha, String message, String authorLogin,
                                      Long authorGithubId, OffsetDateTime authoredAt) {
    }
}
