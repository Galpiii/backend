package com.github.galpiii.galpi.domain.github.client.dto;

import java.util.List;

/**
 * 여러 페이지를 모은 결과와, 그것이 전부인지 여부.
 *
 * <p>화면용 조회는 페이지 상한이나 요청 budget에 걸려도 모은 데까지 돌려준다. 그 사실을
 * 목록과 함께 들고 다니지 않으면 호출하는 쪽은 잘린 목록을 완전한 목록으로 오해한다 —
 * 사용자에게는 "저장소가 없다"와 "여기까지만 읽었다"가 같은 화면으로 보이게 된다.
 *
 * <p>권한 판정용 조회에는 이 타입을 쓰지 않는다. 그쪽은 잘린 목록을 받는 것 자체가 오류라
 * 예외로 끝난다.
 *
 * @param truncated 상한에 걸려 뒤쪽을 읽지 못했으면 {@code true}
 */
public record GithubListResult<T>(List<T> items, boolean truncated) {

    public GithubListResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> GithubListResult<T> of(List<T> items, boolean truncated) {
        return new GithubListResult<>(items, truncated);
    }

    public static <T> GithubListResult<T> empty() {
        return new GithubListResult<>(List.of(), false);
    }
}
