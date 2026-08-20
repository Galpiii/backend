package com.github.galpiii.galpi.domain.project.dto;

import org.springframework.data.domain.Sort;

/**
 * 목록 정렬 기준.
 *
 * <p>정렬 컬럼을 열거형으로 받는다. 클라이언트가 보낸 문자열을 그대로 정렬식에 넣으면
 * 엔티티에 없는 속성이 쿼리로 흘러들어간다.
 */
public enum ProjectSort {

    /** 기본값. 최근에 손댄 프로젝트가 위로 온다. */
    UPDATED_AT(Sort.by(Sort.Direction.DESC, "updatedAt")),

    NAME(Sort.by(Sort.Direction.ASC, "name")),

    CREATED_AT(Sort.by(Sort.Direction.DESC, "createdAt"));

    private final Sort sort;

    ProjectSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        // id를 2차 정렬로 둔다. 같은 시각·같은 이름이 여러 건이면 페이지 경계에서 순서가
        // 흔들려 같은 프로젝트가 두 페이지에 나오거나 아예 빠진다.
        return sort.and(Sort.by(Sort.Direction.DESC, "id"));
    }
}
