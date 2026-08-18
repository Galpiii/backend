package com.github.galpiii.galpi.domain.github.client;

import com.github.galpiii.galpi.domain.github.config.GithubOperationProperties;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.global.error.ErrorCode;

/**
 * 하나의 사용자 작업 전체에서 공유하는 GitHub API 호출 budget과 soft deadline.
 * installation마다 새로 만들면 요청 증폭을 막을 수 없으므로 서비스 진입점에서 한 번만 만든다.
 * deadline은 이미 전송한 요청을 중단하지 않고 다음 요청의 시작만 막는다.
 */
public final class GithubRequestBudget {

    static final String REQUEST_ATTRIBUTE = GithubRequestBudget.class.getName();

    private int remainingRequests;
    private final long deadlineNanos;

    private GithubRequestBudget(int maxRequests, long timeoutNanos) {
        this.remainingRequests = maxRequests;
        // nanoTime은 기준점이 임의라 음수일 수 있다. 차이를 이용하면 덧셈 overflow에도 안전하다.
        this.deadlineNanos = System.nanoTime() + timeoutNanos;
    }

    public static GithubRequestBudget from(GithubOperationProperties properties) {
        return new GithubRequestBudget(properties.maxRequests(), properties.timeout().toNanos());
    }

    /** 최초 요청과 5xx 재시도를 포함해 실제 HTTP 요청을 보내기 직전에 한 번 호출한다. */
    public void consume() {
        if (System.nanoTime() - deadlineNanos >= 0) {
            throw new GithubApiException(ErrorCode.GITHUB_OPERATION_TIMEOUT);
        }
        if (remainingRequests <= 0) {
            throw new GithubApiException(ErrorCode.GITHUB_OPERATION_BUDGET_EXCEEDED);
        }
        remainingRequests--;
    }

    /** 화면용 부분 조회가 다음 installation 요청을 시작하지 않고 멈출 때 사용한다. */
    public boolean isRequestLimitReached() {
        return remainingRequests <= 0;
    }
}
