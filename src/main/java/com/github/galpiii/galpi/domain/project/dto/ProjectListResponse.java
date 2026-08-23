package com.github.galpiii.galpi.domain.project.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 프로젝트 목록 한 페이지.
 *
 * <p>결과가 0건이면 빈 배열을 준다. 빈 상태 화면을 띄울지는 프론트가 정한다 — 필터를 걸어
 * 0건인 것과 프로젝트가 아예 없는 것은 서버가 구분해 줄 수 없다.
 */
public record ProjectListResponse(
        List<ProjectSummaryResponse> projects,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static ProjectListResponse from(Page<ProjectSummaryRow> rows) {
        return new ProjectListResponse(
                rows.getContent().stream().map(ProjectSummaryResponse::from).toList(),
                rows.getNumber(),
                rows.getSize(),
                rows.getTotalElements(),
                rows.getTotalPages());
    }
}
