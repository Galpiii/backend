package com.github.galpiii.galpi.ai.exception;

import com.github.galpiii.galpi.ai.support.AiFailureKind;
import lombok.Getter;

/**
 * PR 요약 호출이 재시도로도 풀리지 않았다.
 *
 * <p>기능명세서 쪽과 예외를 나눈 이유는 받는 사람이 다르기 때문이다. 저쪽은 업로드 화면이
 * 받아 "분석에 실패했습니다" 하나로 처리하고, 이쪽은 워커가 받아 어떤 실패 사유를 행에
 * 남길지 정한다. 그래서 갈래를 함께 들고 다닌다.
 */
@Getter
public class PullRequestSummaryAiException extends RuntimeException {

    private final AiFailureKind kind;

    public PullRequestSummaryAiException(AiFailureKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }
}
