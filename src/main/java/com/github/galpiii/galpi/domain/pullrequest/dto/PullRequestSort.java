package com.github.galpiii.galpi.domain.pullrequest.dto;

import org.springframework.data.domain.Sort;

/**
 * PR 목록 정렬 기준.
 *
 * <p>정렬 컬럼을 열거형으로 받는다. 클라이언트가 보낸 문자열을 그대로 정렬식에 넣으면
 * 엔티티에 없는 속성이 쿼리로 흘러들어간다.
 */
public enum PullRequestSort {

    /** 기본값. 화면의 "최신순"이다. */
    MERGED_AT_DESC(Sort.by(Sort.Direction.DESC, "mergedAt")),

    MERGED_AT_ASC(Sort.by(Sort.Direction.ASC, "mergedAt")),

    NUMBER_DESC(Sort.by(Sort.Direction.DESC, "number")),

    NUMBER_ASC(Sort.by(Sort.Direction.ASC, "number"));

    private final Sort sort;

    PullRequestSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        // id를 2차 정렬로 둔다. 같은 시각에 병합된 PR이 여럿이면 페이지 경계에서 순서가
        // 흔들려 같은 PR이 두 페이지에 나오거나 아예 빠진다. 저장소가 여럿인 목록이라
        // 같은 merged_at이 실제로 겹칠 수 있다.
        return sort.and(Sort.by(Sort.Direction.DESC, "id"));
    }
}
