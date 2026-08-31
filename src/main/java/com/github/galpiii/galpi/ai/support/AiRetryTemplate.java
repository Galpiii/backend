package com.github.galpiii.galpi.ai.support;

import com.github.galpiii.galpi.ai.exception.RetryableAiException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.RateLimitException;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * OpenAI 호출의 재시도·백오프·예산 처리.
 *
 * <p>기능명세서 추출과 PR 요약이 같은 규칙을 쓴다. 도메인마다 다시 구현하면 "429는 다시 걸고
 * 400은 걸지 않는다" 같은 판단이 두 곳에 흩어져 한쪽만 고쳐지는 일이 생긴다. 그래서 판단을
 * 여기 모으고, 도메인이 정하는 것은 두 가지만 남긴다 -- 로그에 붙일 이름과, 최종 실패에
 * 쓸 예외 종류.
 *
 * <p>이 클래스는 상태가 없다. 호출마다 만들어도 되고 서비스가 필드로 들고 있어도 된다.
 */
@Slf4j
public final class AiRetryTemplate {

    /**
     * 재시도로 풀리지 않는 실패를 감쌀 예외를 만든다.
     *
     * <p>도메인마다 다르다. 기능명세서 쪽은 업로드 화면이 받는 예외이고, PR 요약 쪽은 워커가
     * 받아 실패 사유로 번역하는 예외라 같은 타입일 이유가 없다.
     */
    @FunctionalInterface
    public interface TerminalFailureFactory {
        RuntimeException create(AiFailureKind kind, String message, Throwable cause);
    }

    private final String logTag;
    private final int maxAttempts;
    private final Duration retryBackoff;
    private final TerminalFailureFactory terminalFailure;

    /**
     * @param logTag 로그 앞머리. {@code "[PR 요약]"}처럼 대괄호까지 포함한 값을 넘긴다
     */
    public AiRetryTemplate(String logTag, int maxAttempts, Duration retryBackoff,
                           TerminalFailureFactory terminalFailure) {
        this.logTag = logTag;
        this.maxAttempts = maxAttempts;
        this.retryBackoff = retryBackoff;
        this.terminalFailure = terminalFailure;
    }

    /**
     * 예산 안에서 재시도한다.
     *
     * <p>시도 전에 예산을 먼저 본다. 남은 시간이 없는데 한 번 더 걸면, 타임아웃이 긴 호출
     * 하나가 예산을 몇 배로 넘긴다.
     *
     * <p>{@code action}은 실패를 {@link RetryableAiException}으로 던져 재시도를 요청한다.
     * 그 밖의 예외는 그대로 밖으로 나간다 -- 다시 걸어도 결과가 같은 실패이기 때문이다.
     */
    public <T> T execute(String operation, Instant deadline, Supplier<T> action) {
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (Instant.now().isAfter(deadline)) {
                log.warn("{} 분석 예산이 끝나 {}를 더 시도하지 않습니다. attempt: {}/{}",
                        logTag, operation, attempt, maxAttempts);
                throw terminalFailure.create(AiFailureKind.BUDGET_EXHAUSTED,
                        operation + "가 분석 예산 안에 끝나지 않았습니다.", lastFailure);
            }

            try {
                return action.get();
            } catch (RetryableAiException e) {
                lastFailure = e;
                log.warn("{} {} 실패. attempt: {}/{}, reason: {}",
                        logTag, operation, attempt, maxAttempts, reasonOf(e));

                if (attempt < maxAttempts) {
                    sleepBeforeRetry(attempt);
                }
            }
        }

        throw terminalFailure.create(AiFailureKind.RETRIES_EXHAUSTED,
                operation + "에 최종 실패했습니다.", lastFailure);
    }

    /**
     * OpenAI SDK 예외를 재시도 가능 여부로 나눈다.
     *
     * <p>429·5xx·네트워크 오류만 다시 건다. 400·401·404는 같은 요청을 다시 보내도 같은 답이
     * 오므로, 재시도는 지연과 비용만 늘린다.
     */
    public RuntimeException classify(RuntimeException e) {
        if (e instanceof RateLimitException
                || e instanceof InternalServerException
                || e instanceof OpenAIIoException
                || e instanceof OpenAIRetryableException) {
            return new RetryableAiException("OpenAI 호출에 실패했습니다.", e);
        }

        return terminalFailure.create(AiFailureKind.CALL_FAILED, "OpenAI 호출에 실패했습니다.", e);
    }

    /**
     * 재시도 로그에 남길 실패 사유.
     *
     * <p>{@link #classify}가 감싸면서 붙인 메시지는 모든 실패에 대해 같은 문구라 그것만 남기면
     * 타임아웃인지 429인지 알 수 없다. 재시도했다는 사실보다 무엇 때문이었는지가 원인 추적에
     * 필요하다.
     */
    private static String reasonOf(RetryableAiException e) {
        Throwable cause = e.getCause();

        if (cause == null) {
            return e.getMessage();
        }

        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(retryBackoff.toMillis() << (attempt - 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw terminalFailure.create(AiFailureKind.INTERRUPTED,
                    "분석 대기 중 인터럽트되었습니다.", e);
        }
    }
}
