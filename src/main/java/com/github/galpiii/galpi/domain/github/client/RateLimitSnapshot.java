package com.github.galpiii.galpi.domain.github.client;

import org.springframework.http.HttpHeaders;

import java.time.Instant;

public record RateLimitSnapshot(
        Integer limit,
        Integer remaining,
        Integer used,
        Instant resetAt,
        String resource
) {

    public static final String HEADER_LIMIT = "x-ratelimit-limit";
    public static final String HEADER_REMAINING = "x-ratelimit-remaining";
    public static final String HEADER_USED = "x-ratelimit-used";
    public static final String HEADER_RESET = "x-ratelimit-reset";
    public static final String HEADER_RESOURCE = "x-ratelimit-resource";

    public static RateLimitSnapshot from(HttpHeaders headers) {
        Long reset = parseLong(headers.getFirst(HEADER_RESET));
        return new RateLimitSnapshot(
                parseInt(headers.getFirst(HEADER_LIMIT)),
                parseInt(headers.getFirst(HEADER_REMAINING)),
                parseInt(headers.getFirst(HEADER_USED)),
                reset == null ? null : Instant.ofEpochSecond(reset),
                headers.getFirst(HEADER_RESOURCE)
        );
    }

    public boolean isPresent() {
        return limit != null || remaining != null;
    }

    public boolean isExhausted() {
        return remaining != null && remaining <= 0;
    }

    private static Integer parseInt(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
