package com.github.galpiii.galpi.ai.exception;

import com.github.galpiii.galpi.ai.support.AiFailureKind;
import lombok.Getter;

/**
 * SDK 실패와 호출 예산 초과를 구분하여 DB 큐의 재시도 정책으로 전달한다.
 */
@Getter
public class FeatureMatchingAiException extends RuntimeException {

    private final AiFailureKind kind;

    public FeatureMatchingAiException(AiFailureKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }
}
