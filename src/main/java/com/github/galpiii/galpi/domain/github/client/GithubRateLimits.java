package com.github.galpiii.galpi.domain.github.client;

import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * 403이 rate limit인지 권한 오류인지 가르는 판단과 재시도 시각 계산.
 *
 * <p>클라이언트마다 따로 두면 안 되는 로직이다. 한쪽만 secondary limit을 못 알아보면 그 경로의
 * 403이 권한 오류로 둔갑해 저장소가 INACCESSIBLE로 표시된다 — 실제로는 잠시 후 되는 요청인데
 * 사용자에게는 권한을 잃은 것처럼 보인다.
 */
final class GithubRateLimits {

    private static final long DEFAULT_RETRY_AFTER_SECONDS = 60L;

    private GithubRateLimits() {
    }

    /** 403이 이미 확인된 뒤에만 부른다. 이 검사만으로 rate limit을 판정하지 않는다. */
    static boolean isRateLimited(HttpHeaders headers, String body) {
        return RateLimitSnapshot.from(headers).isExhausted()
                || headers.getFirst(HttpHeaders.RETRY_AFTER) != null
                || isSecondaryRateLimit(body);
    }

    static boolean isSecondaryRateLimit(String body) {
        // GitHub은 secondary limit의 안정적인 machine-readable code를 제공하지 않는다.
        // 호출부가 403으로 먼저 한정한 뒤 공식 영문 메시지를 best-effort로 식별한다.
        if (body == null || body.isBlank()) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("secondary rate limit")
                || normalized.contains("abuse detection mechanism");
    }

    /** {@code Retry-After}가 있으면 그쪽이 우선이다. secondary limit은 primary reset과 무관하다. */
    static long retryAfterSeconds(HttpHeaders headers, RateLimitSnapshot snapshot) {
        String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter != null) {
            try {
                return Math.max(1L, Long.parseLong(retryAfter));
            } catch (NumberFormatException ignored) {
                // GitHub이 정수 초가 아닌 값을 보내면 primary reset 시각이나 보수적 기본값을 쓴다.
            }
        }
        if (snapshot.resetAt() != null) {
            long millis = Duration.between(Instant.now(), snapshot.resetAt()).toMillis();
            return Math.max(1L, (millis + 999L) / 1_000L);
        }
        return DEFAULT_RETRY_AFTER_SECONDS;
    }
}
