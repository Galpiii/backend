package com.github.galpiii.galpi.domain.pullrequest.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * PR 목록 한 페이지.
 *
 * <p>결과가 0건이면 빈 배열을 준다. 필터를 걸어 0건인 것과 PR이 아예 없는 것은 서버가
 * 구분해 줄 수 없다 -- 빈 상태 화면을 어떻게 띄울지는 프론트가 정한다.
 */
public record PullRequestListResponse(
        List<PullRequestListItemResponse> pullRequests,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static PullRequestListResponse from(Page<PullRequestListRow> rows) {
        return new PullRequestListResponse(
                rows.getContent().stream().map(PullRequestListItemResponse::from).toList(),
                rows.getNumber(),
                rows.getSize(),
                rows.getTotalElements(),
                rows.getTotalPages());
    }

    /** 프로젝트에 살아 있는 저장소가 없을 때. 쿼리를 돌리지 않고 빈 페이지를 만든다. */
    public static PullRequestListResponse empty(int page, int size) {
        return new PullRequestListResponse(List.of(), page, size, 0L, 0);
    }
}
