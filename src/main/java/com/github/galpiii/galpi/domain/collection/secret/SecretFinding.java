package com.github.galpiii.galpi.domain.collection.secret;

/**
 * 비밀정보 탐지 한 건. 종류와 줄 번호만 담는다.
 *
 * <p>탐지된 값을 필드로 두지 않는 것이 이 record의 존재 이유다. 값을 담을 수 있게 만들어 두면
 * 언젠가 누군가 그것을 로그나 응답에 찍는다.
 *
 * @param line 1부터 센 줄 번호. 줄 개념이 없는 짧은 텍스트에서는 1이다
 */
public record SecretFinding(SecretPatternKind kind, int line) {

    public static SecretFinding at(SecretPatternKind kind, int line) {
        return new SecretFinding(kind, line);
    }
}
