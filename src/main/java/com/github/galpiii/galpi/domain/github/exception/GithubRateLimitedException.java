package com.github.galpiii.galpi.domain.github.exception;

import com.github.galpiii.galpi.global.error.ErrorCode;

/** GitHub의 재시도 가능 시각을 API 클라이언트까지 전달하는 rate-limit 예외. */
public class GithubRateLimitedException extends GithubApiException {

    private final long retryAfterSeconds;

    public GithubRateLimitedException(long retryAfterSeconds) {
        super(ErrorCode.GITHUB_RATE_LIMITED);
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
