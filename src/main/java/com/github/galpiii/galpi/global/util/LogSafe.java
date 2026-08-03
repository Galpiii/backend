package com.github.galpiii.galpi.global.util;

import java.util.regex.Pattern;

/**
 * 바깥에서 들어온 문자열을 로그에 남기기 전에 손질한다.
 * <p>
 * 콜백 쿼리 파라미터처럼 공격자가 값을 정하는 문자열을 그대로 찍으면, 개행을 섞어 가짜 로그 줄을
 * 심을 수 있고(로그 인젝션) 길이 제한도 없어 로그를 통째로 밀어낼 수 있다. 제어문자를 지우고
 * 길이를 자르며, 혹시 섞여 들어온 비밀값은 {@link TokenMasker}로 함께 가린다.
 */
public final class LogSafe {

    private static final int MAX_LENGTH = 200;
    private static final String TRUNCATED = "…(생략)";
    private static final String NONE = "<none>";

    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}+");

    private LogSafe() {
    }

    public static String text(String value) {
        if (value == null) {
            return NONE;
        }
        String masked = TokenMasker.mask(value);
        String flattened = CONTROL_CHARS.matcher(masked).replaceAll(" ").trim();
        if (flattened.isEmpty()) {
            return NONE;
        }
        return flattened.length() <= MAX_LENGTH
                ? flattened
                : flattened.substring(0, MAX_LENGTH) + TRUNCATED;
    }
}
