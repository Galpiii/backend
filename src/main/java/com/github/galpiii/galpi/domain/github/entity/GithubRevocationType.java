package com.github.galpiii.galpi.domain.github.entity;

/**
 * 폐기 대기 항목이 어떤 폐기를 기다리는지.
 *
 * <p>둘을 섞으면 안 된다. {@link #GRANT}는 이 사용자의 App authorization 전체를 지우므로,
 * 재로그인으로 밀려난 이전 토큰에 쓰면 방금 발급받은 새 토큰까지 함께 죽는다. 반대로 연결
 * 해제에 {@link #TOKEN}만 쓰면 GitHub 쪽에 authorization이 남는다.
 */
public enum GithubRevocationType {

    /** 토큰 하나만 폐기한다. {@code DELETE /applications/{client_id}/token} */
    TOKEN,

    /** App authorization 전체를 폐기한다. {@code DELETE /applications/{client_id}/grant} */
    GRANT
}
