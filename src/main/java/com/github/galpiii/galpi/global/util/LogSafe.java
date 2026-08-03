package com.github.galpiii.galpi.global.util;

import java.util.regex.Pattern;

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
