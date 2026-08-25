package com.github.galpiii.galpi.domain.featurespec.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 기능 일반 수정. 기능명과 세부 요구사항을 한 번에 확정한다.
 *
 * <p>수정 내용을 모아 두었다가 나중에 저장하는 임시 저장은 없다. 사용자가 확정하면 그대로
 * DB에 반영된다.
 *
 * <p>{@code requirements}는 <b>전체 치환</b>이다. 보낸 목록이 곧 저장 후의 목록이 된다.
 * <ul>
 *   <li>{@code id}가 있으면 그 요구사항을 수정한다
 *   <li>{@code id}가 없으면 새로 만든다. 사용자가 직접 쓴 문장이라 원문 근거는 비어 있다
 *   <li>기존 요구사항이 목록에서 빠져 있으면 삭제한다
 *   <li>표시 순서는 배열 순서를 그대로 쓴다
 * </ul>
 *
 * <p>필드가 <b>없는 것</b>과 <b>빈 배열</b>은 다르다. 없으면 요구사항을 건드리지 않고,
 * 빈 배열이면 전부 삭제한다. PATCH라 "보내지 않은 것은 그대로"가 기본이고, 요구사항을 모두
 * 지우는 것은 사용자가 실제로 할 수 있는 선택이라 표현할 방법이 필요하다.
 */
public record FeatureUpdateRequest(

        @Size(min = 1, max = 255, message = "기능명은 1자 이상 255자 이하여야 합니다.")
        String name,

        @Valid
        @Size(max = MAX_REQUIREMENTS, message = "세부 요구사항은 100개를 넘을 수 없습니다.")
        List<@NotNull(message = "세부 요구사항 항목이 비어 있습니다.") Requirement> requirements
) {

    public static final int MAX_REQUIREMENTS = 100;
    public static final int MAX_CONTENT_LENGTH = 2000;

    public FeatureUpdateRequest {
        name = name == null ? null : name.strip();
    }

    @JsonIgnore
    public boolean isEmpty() {
        return name == null && requirements == null;
    }

    public record Requirement(

            Long id,

            @NotBlank(message = "세부 요구사항 내용은 비어 있을 수 없습니다.")
            @Size(max = MAX_CONTENT_LENGTH, message = "세부 요구사항 내용은 2000자를 넘을 수 없습니다.")
            String content
    ) {

        public Requirement {
            content = content == null ? null : content.strip();
        }
    }
}
