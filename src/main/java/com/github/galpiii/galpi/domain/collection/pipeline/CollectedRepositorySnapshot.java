package com.github.galpiii.galpi.domain.collection.pipeline;

import com.github.galpiii.galpi.domain.collection.entity.ChangeStatus;
import com.github.galpiii.galpi.domain.collection.entity.DataCompleteness;
import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 수집기가 분석 파이프라인에 넘기는 구조 전체.
 *
 * <p>이름을 {@code RepositorySnapshot}으로 두지 못한 것은 1B가 이미 그 이름을 저장소 메타데이터
 * record로 쓰고 있기 때문이다. 담는 것이 다르다 — 저쪽은 GitHub 목록 조회 결과이고, 이쪽은
 * 한 커밋에서 실제로 뽑아낸 파일과 PR이다.
 *
 * <p><b>여기 담기는 코드는 DB로 가지 않는다.</b> {@link CollectedFile#contentRef()}는 임시
 * 파일 참조이고, {@link CollectedPullRequestFile#patch()}는 이 객체 안에서만 존재한다. 둘 다
 * 인계가 끝나면 사라진다.
 *
 * <p>청킹 전략·토큰 예산·프롬프트 구성·기능 대조 로직은 이 계약의 범위 밖이다. 수집기는
 * "무엇을 모았고 무엇이 빠졌는지"까지만 책임진다.
 *
 * @param fileTree 제외된 파일까지 포함한 전체 경로 목록. 파이프라인이 저장소 구조를 보는 창이다
 */
public record CollectedRepositorySnapshot(
        Long githubRepositoryId,
        String fullName,
        String commitSha,
        List<String> fileTree,
        List<CollectedFile> collectedFiles,
        List<ExcludedFile> excludedFiles,
        List<CollectedPullRequest> pullRequests,
        DataCompleteness dataCompleteness,
        List<IncompleteReason> incompleteReasons
) {

    public long collectedBytes() {
        return collectedFiles.stream().mapToLong(CollectedFile::sizeBytes).sum();
    }

    /**
     * @param truncated 상한 때문에 내용 일부만 담긴 파일
     * @param language  확장자로 추정한 언어. 확정이 아니라 파이프라인의 힌트다
     */
    public record CollectedFile(String path, ContentRef contentRef, long sizeBytes,
                                boolean truncated, String language) {
    }

    public record ExcludedFile(String path, ExclusionReason reason) {
    }

    /**
     * @param body 마스킹을 거친 본문. 원문이 아니다
     */
    public record CollectedPullRequest(int number,
                                       String title,
                                       String body,
                                       OffsetDateTime mergedAt,
                                       List<CollectedPullRequestFile> files,
                                       List<CollectedCommit> commits,
                                       DataCompleteness dataCompleteness,
                                       List<IncompleteReason> incompleteReasons) {
    }

    /**
     * @param patch diff 원문. <b>이 객체에만 존재하고 DB로 가지 않는다.</b> GitHub이 생략했으면
     *              {@code null}이며 그때 {@code patchOmitted}가 참이다
     */
    public record CollectedPullRequestFile(String path,
                                           String previousPath,
                                           ChangeStatus changeStatus,
                                           int additions,
                                           int deletions,
                                           int changes,
                                           String patch,
                                           boolean patchOmitted) {
    }

    /**
     * @param message 마스킹을 거친 커밋 메시지
     */
    public record CollectedCommit(String sha,
                                  String message,
                                  String authorLogin,
                                  OffsetDateTime authoredAt) {
    }
}
