package com.github.galpiii.galpi.domain.pullrequest.dto;

import com.github.galpiii.galpi.domain.collection.entity.ChangeStatus;
import com.github.galpiii.galpi.domain.collection.entity.DataCompleteness;
import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import com.github.galpiii.galpi.domain.collection.entity.PullRequestCommit;
import com.github.galpiii.galpi.domain.collection.entity.PullRequestFile;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysis;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * PR 상세. 화면의 "GITHUB 원본" 카드와 "AI 분석" 카드를 함께 채운다.
 *
 * <p><b>{@code patch} 필드는 없다.</b> DB에 컬럼이 없고 응답에도 나가지 않는다 -- diff 원문은
 * 요약을 만드는 순간에만 메모리에 있고 어디에도 남지 않는다. 화면이 변경 내용을 보여줘야
 * 하면 {@code htmlUrl}로 GitHub에 보낸다.
 *
 * <p>"관련 기능" 카드를 채울 필드도 없다. 기능대조가 아직 없어 빈 배열조차 내리지 않는다 --
 * 빈 배열은 "대조했는데 없다"로 읽히고, 지금은 "대조하지 않았다"가 맞다.
 *
 * @param body     이미 마스킹을 거친 값이다. 수집 시점에 자격증명 패턴을 검사했고, 그대로 내린다
 * @param state    항상 {@code MERGED}. {@link PullRequestListItemResponse} 참조
 * @param filesTruncated 변경 파일이 상한을 넘어 잘렸는지. 화면이 "일부만 표시" 문구를 붙인다
 * @param commitsTruncated 커밋이 상한을 넘어 잘렸는지
 */
public record PullRequestDetailResponse(
        Long id,
        Integer number,
        String title,
        String body,
        String state,
        String baseRef,
        String headRef,
        OffsetDateTime mergedAt,
        OffsetDateTime createdAtGithub,
        String htmlUrl,
        PullRequestAuthorResponse author,
        PullRequestRepositoryResponse repository,
        Stats stats,
        List<FileResponse> files,
        boolean filesTruncated,
        List<CommitResponse> commits,
        boolean commitsTruncated,
        PullRequestAnalysisResponse analysis,
        DataCompleteness dataCompleteness,
        List<IncompleteReason> incompleteReasons
) {

    public static PullRequestDetailResponse of(PullRequest pullRequest,
                                               GithubRepository repository,
                                               List<PullRequestFile> files,
                                               boolean filesTruncated,
                                               List<PullRequestCommit> commits,
                                               boolean commitsTruncated,
                                               PullRequestAnalysis analysis) {
        return new PullRequestDetailResponse(
                pullRequest.getId(),
                pullRequest.getNumber(),
                pullRequest.getTitle(),
                pullRequest.getBody(),
                PullRequestListItemResponse.MERGED_STATE,
                pullRequest.getBaseRef(),
                pullRequest.getHeadRef(),
                pullRequest.getMergedAt(),
                pullRequest.getCreatedAtGithub(),
                pullRequest.getHtmlUrl(),
                author(pullRequest),
                new PullRequestRepositoryResponse(repository.getId(), repository.getFullName()),
                new Stats(pullRequest.getChangedFilesCount(), pullRequest.getAdditions(),
                        pullRequest.getDeletions()),
                files.stream().map(FileResponse::from).toList(),
                filesTruncated,
                commits.stream().map(CommitResponse::from).toList(),
                commitsTruncated,
                PullRequestAnalysisResponse.from(analysis),
                pullRequest.getDataCompleteness(),
                pullRequest.getIncompleteReasons() == null
                        ? List.of()
                        : pullRequest.getIncompleteReasons());
    }

    private static PullRequestAuthorResponse author(PullRequest pullRequest) {
        return pullRequest.getContributor() == null
                ? null
                : PullRequestAuthorResponse.of(pullRequest.getContributor().getLogin(),
                        pullRequest.getContributor().getAvatarUrl());
    }

    /**
     * @param changedFilesCount GitHub이 보고한 값이다. {@code files}의 크기와 다를 수 있다 --
     *                          수집이나 응답에서 파일 목록이 잘렸을 때가 그렇다
     */
    public record Stats(int changedFilesCount, int additions, int deletions) {
    }

    /**
     * @param patchOmitted GitHub이 이 파일의 diff를 응답에서 뺐다는 뜻이다. 바이너리이거나
     *                     변경이 너무 커서인데, 어느 쪽인지는 GitHub이 알려 주지 않는다
     */
    public record FileResponse(String path, String previousPath, ChangeStatus changeStatus,
                               int additions, int deletions, int changes, boolean patchOmitted) {

        static FileResponse from(PullRequestFile file) {
            return new FileResponse(file.getPath(), file.getPreviousPath(), file.getChangeStatus(),
                    file.getAdditions(), file.getDeletions(), file.getChanges(),
                    file.isPatchOmitted());
        }
    }

    /** @param message 마스킹을 거친 값이다. {@code body}와 같은 규칙이다 */
    public record CommitResponse(String sha, String message, String authorLogin,
                                 OffsetDateTime authoredAt) {

        static CommitResponse from(PullRequestCommit commit) {
            return new CommitResponse(commit.getSha(), commit.getMessage(),
                    commit.getAuthorLogin(), commit.getAuthoredAt());
        }
    }
}
