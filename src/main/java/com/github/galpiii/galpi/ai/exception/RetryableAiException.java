package com.github.galpiii.galpi.ai.exception;

public class RetryableAiException extends RuntimeException {

    public RetryableAiException(String message) {
        super(message);
    }

    public RetryableAiException(String message, Throwable cause) {
        super(message, cause);
    }
}
