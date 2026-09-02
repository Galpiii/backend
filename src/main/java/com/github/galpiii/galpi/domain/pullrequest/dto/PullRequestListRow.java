package com.github.galpiii.galpi.domain.pullrequest.dto;

import com.github.galpiii.galpi.domain.collection.entity.DataCompleteness;
import com.github.galpiii.galpi.domain.pullrequest.entity.ChangeType;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.domain.pullrequest.entity.SummaryFailureCode;

import java.time.OffsetDateTime;

/**
 * 목록 쿼리가 한 번에 읽어 오는 한 행.
 *
 * <p>응답 형태와 분리해 둔 것은 조인 결과를 그대로 담기 때문이다. 저장소·작성자·요약을
 * 항목마다 다시 조회하면 그대로 N+1이 되므로 이 한 쿼리 안에서 전부 읽는다. 화면에 나가는
 * 모양은 {@link PullRequestListItemResponse}가 정한다.
 *
 * <p>{@code summary} 본문은 여기 없다. 목록이 쓰지 않고, 수십 건 x 300자를 필터를 바꿀
 * 때마다 실어 나를 이유가 없다 -- 상세에서만 준다.
 *
 * @param authorLogin  {@code contributor_id}가 비어 있으면 {@code null}이다. GitHub 계정이
 *                     삭제된 PR이고, 그 사실을 화면이 "알 수 없음"으로 그린다
 * @param exclusionCount {@code pr_exclusions}에 걸린 수. 유니크 제약 때문에 0 아니면 1이다
 */
public record PullRequestListRow(
        Long id,
        Integer number,
        String title,
        OffsetDateTime mergedAt,
        String htmlUrl,
        DataCompleteness dataCompleteness,
        String authorLogin,
        String authorAvatarUrl,
        Long repositoryId,
        String repositoryFullName,
        PullRequestAnalysisStatus analysisStatus,
        ChangeType changeType,
        OffsetDateTime analyzedAt,
        SummaryFailureCode analysisErrorCode,
        Long exclusionCount
) {
}
