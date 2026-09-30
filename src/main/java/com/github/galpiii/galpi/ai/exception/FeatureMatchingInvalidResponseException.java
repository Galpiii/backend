package com.github.galpiii.galpi.ai.exception;

public class FeatureMatchingInvalidResponseException extends RuntimeException {
    public FeatureMatchingInvalidResponseException() {
        this("INVALID_RESPONSE");
    }

    public FeatureMatchingInvalidResponseException(String rule) {
        super(rule);
    }
}
