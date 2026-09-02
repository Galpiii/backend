package com.github.galpiii.galpi.domain.pullrequest.support;

import com.github.galpiii.galpi.domain.pullrequest.entity.SummaryFailureCode;
import lombok.Getter;

/** 외부 전송 직전 현재 claim 또는 실행 근거가 더 이상 유효하지 않다. */
@Getter
public class SummaryHandoffAbandonedException extends RuntimeException {

    private final SummaryFailureCode reason;

    public SummaryHandoffAbandonedException(SummaryFailureCode reason) {
        this.reason = reason;
    }

    public static SummaryHandoffAbandonedException staleClaim() {
        return new SummaryHandoffAbandonedException(null);
    }
}
