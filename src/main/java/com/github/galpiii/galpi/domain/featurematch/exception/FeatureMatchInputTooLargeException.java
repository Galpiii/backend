package com.github.galpiii.galpi.domain.featurematch.exception;

/**
 * 생략할 수 없는 기능 목록 자체가 입력 예산을 넘었다. HTTP 의미는 API 계층에서 붙인다.
 */
public class FeatureMatchInputTooLargeException extends RuntimeException {
    public FeatureMatchInputTooLargeException() {
        super("필수 기능 목록이 기능대조 입력 예산을 초과했습니다.");
    }
}
