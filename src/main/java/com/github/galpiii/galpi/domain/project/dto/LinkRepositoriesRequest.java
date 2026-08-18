package com.github.galpiii.galpi.domain.project.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * <p>같은 id가 중복으로 와도 거부하지 않는다. 서버가 한 번만 저장하므로 사용자가 고친다고
 * 달라질 것이 없는 요청이고, 400으로 돌려보내면 프론트만 번거로워진다.
 *
 * <p>상한은 한 번에 고를 수 있는 저장소 수를 제한할 뿐, GitHub 호출량을 막지는 못한다.
 * 권한 재검증은 요청한 id 수가 아니라 사용자의 installation 수만큼 호출된다.
 */
public record LinkRepositoriesRequest(
        @NotEmpty(message = "연결할 저장소를 하나 이상 선택해야 합니다.")
        @Size(max = MAX_REPOSITORIES,
                message = "한 번에 연결할 수 있는 저장소는 " + MAX_REPOSITORIES + "개까지입니다.")
        List<@NotNull @Positive(message = "저장소 id는 양수여야 합니다.") Long> githubRepositoryIds
) {

    public static final int MAX_REPOSITORIES = 100;
}
