package com.github.galpiii.galpi.domain.featurespec.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * AI가 추천한 분리안대로 기능 하나를 여러 기능으로 나눈다.
 *
 * <p>사용자가 고칠 수 있는 것은 각 기능의 이름뿐이다. 요구사항을 다른 기능으로 옮기거나
 * 추천 기능을 더하고 빼는 자유 편집은 제공하지 않는다 — 어느 요구사항이 어디로 갈지는
 * 이미 저장된 추천안이 정한다.
 *
 * <p>추천안 전체를 보내야 한다. 일부만 골라 적용할 수는 없다.
 */
public record FeatureSplitRequest(

        @Valid
        @NotEmpty(message = "적용할 분리 추천안이 없습니다.")
        List<Target> features
) {

    public record Target(

            @NotNull(message = "적용할 분리 추천안을 선택해 주세요.")
            Long suggestionId,

            @NotNull(message = "기능명을 입력해 주세요.")
            @Size(min = 1, max = 255, message = "기능명은 1자 이상 255자 이하여야 합니다.")
            String name
    ) {

        public Target {
            name = name == null ? null : name.strip();
        }
    }
}
