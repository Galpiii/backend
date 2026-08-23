package com.github.galpiii.galpi.domain.project.dto;

import com.github.galpiii.galpi.domain.project.entity.Project;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 프로젝트 생성 요청.
 *
 * <p>{@code ownerId}는 받지 않는다. 본문에 넣어 보내도 여기 자리가 없어 그대로 버려지고,
 * 소유자는 인증된 사용자로 서버가 채운다.
 *
 * <p>PDF는 이 요청에 싣지 않는다. 프로젝트 생성과 명세서 업로드는 별개 트랜잭션이라,
 * 업로드가 실패해도 프로젝트는 남고 사용자는 저장소 연결로 넘어가거나 나중에 다시 올릴 수 있다.
 */
public record ProjectCreateRequest(
        @NotBlank(message = "프로젝트 이름을 입력해 주세요.")
        @Size(max = Project.MAX_NAME_LENGTH,
                message = "프로젝트 이름은 100자를 넘을 수 없습니다.")
        String name
) {

    /** 공백만 있는 이름을 통과시키지 않으려고 검증 전에 다듬는다. */
    public ProjectCreateRequest {
        name = name == null ? null : name.strip();
    }
}
