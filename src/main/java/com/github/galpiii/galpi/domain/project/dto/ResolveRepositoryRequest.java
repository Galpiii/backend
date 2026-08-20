package com.github.galpiii.galpi.domain.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * GitHub 저장소 URL을 붙여 넣어 목록에 없는 저장소를 찾는다.
 *
 * <p>여기서는 확인만 한다. 실제 연결은 확인된 {@code githubRepositoryId}를 가지고
 * {@code POST /projects/{id}/repositories}로 한다.
 */
public record ResolveRepositoryRequest(
        @NotBlank(message = "저장소 URL을 입력해 주세요.")
        @Size(max = 500, message = "저장소 URL이 너무 깁니다.")
        String url
) {

    public ResolveRepositoryRequest {
        url = url == null ? null : url.strip();
    }
}
