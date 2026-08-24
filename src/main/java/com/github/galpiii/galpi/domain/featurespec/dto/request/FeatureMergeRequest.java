package com.github.galpiii.galpi.domain.featurespec.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 중복으로 지목된 두 기능을 하나로 합친다.
 *
 * <p>합칠 상대와 최종 기능명만 보낸다. 어느 요구사항을 가져갈지, 어느 분류에 넣을지는 서버가
 * 이미 저장된 중복 후보를 보고 정한다.
 *
 * <p>AI가 중복으로 지목한 쌍만 합칠 수 있다. 임의의 두 기능을 합치는 기능은 없다.
 */
public record FeatureMergeRequest(

        @NotNull(message = "합칠 기능을 선택해 주세요.")
        Long targetFeatureId,

        @NotNull(message = "기능명을 입력해 주세요.")
        @Size(min = 1, max = 255, message = "기능명은 1자 이상 255자 이하여야 합니다.")
        String name
) {

    public FeatureMergeRequest {
        name = name == null ? null : name.strip();
    }
}
