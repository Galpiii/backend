package com.github.galpiii.galpi.domain.collection.secret;

/**
 * 탐지된 비밀정보의 종류.
 *
 * <p>탐지 결과로 밖에 나가는 것은 이 enum과 위치뿐이다. 값 자체는 어디에도 담지 않는다 —
 * 로그에 남기지 않는 것만으로는 부족하고, 예외 메시지나 응답 DTO를 타고 나가는 경로도 함께
 * 막아야 한다. 그래서 탐지기가 값을 아예 반환하지 않는 형태로 만들었다.
 */
public enum SecretPatternKind {

    /** ghp_ / gho_ / ghu_ / ghs_ / ghr_ / github_pat_ */
    GITHUB_TOKEN,

    /** AKIA / ASIA로 시작하는 AWS access key id */
    AWS_ACCESS_KEY,

    /** -----BEGIN ... PRIVATE KEY----- 블록 */
    PRIVATE_KEY_BLOCK,

    /** header.payload.signature 형태의 JWT */
    JWT,

    /** api_key / secret / password / token 등에 긴 리터럴을 대입한 형태 */
    ASSIGNED_CREDENTIAL,

    /** 위 어디에도 걸리지 않지만 무작위에 가까운 고엔트로피 문자열 */
    HIGH_ENTROPY_STRING
}
