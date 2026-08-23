package com.github.galpiii.galpi.domain.consent.exception;

import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ForbiddenException;

/**
 * 현재 정책에 대한 외부 AI 전송 동의가 없다.
 *
 * <p>{@link ForbiddenException}을 그대로 쓰지 않고 종류를 나눈 이유는 워커 때문이다. 수집
 * 도중에 이 예외가 올라오면 저장소 하나의 실패가 아니라 <b>작업 전체를 멈춰야 할 사유</b>다.
 * 다른 403과 섞이면 그 구분을 잃는다.
 */
public class AiDataConsentRequiredException extends ForbiddenException {

    public AiDataConsentRequiredException() {
        super(ErrorCode.AI_DATA_CONSENT_REQUIRED);
    }
}
