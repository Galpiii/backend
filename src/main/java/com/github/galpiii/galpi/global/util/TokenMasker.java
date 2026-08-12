package com.github.galpiii.galpi.global.util;

import java.util.regex.Pattern;

public final class TokenMasker {

    private static final String MASK = "***";

    private static final Pattern GITHUB_TOKEN =
            Pattern.compile("\\b(gh[pousr]_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{20,})\\b");

    private static final Pattern AUTHORIZATION =
            Pattern.compile("(?i)(authorization\\s*[:=]\\s*)(bearer|token|basic)?\\s*\\S+");

    private static final Pattern JWT =
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\b");

    private static final Pattern SENSITIVE_QUERY_PARAM = Pattern.compile(
            "(?i)([?&](?:access_token|refresh_token|client_secret|code|state|token)=)[^&\\s]+");

    private static final Pattern PEM_BLOCK =
            Pattern.compile("-----BEGIN[^-]*PRIVATE KEY-----[\\s\\S]*?-----END[^-]*PRIVATE KEY-----");

    private TokenMasker() {
    }

    public static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String masked = PEM_BLOCK.matcher(value).replaceAll(MASK);
        masked = GITHUB_TOKEN.matcher(masked).replaceAll(MASK);
        masked = JWT.matcher(masked).replaceAll(MASK);
        masked = AUTHORIZATION.matcher(masked).replaceAll("$1" + MASK);
        return SENSITIVE_QUERY_PARAM.matcher(masked).replaceAll("$1" + MASK);
    }
}
