package com.github.galpiii.galpi.domain.analysis.entity;

/** 분석 작업 전체의 상태. */
public enum AnalysisRunStatus {

    /** 만들어졌고 아직 워커가 집어가지 않았다. */
    QUEUED,

    /** 워커가 선점해 진행 중이다. */
    RUNNING,

    /**
     * rate limit에 걸려 중단됐다.
     *
     * <p>Phase 1은 여기서 자동으로 재개하지 않는다. {@code rateLimitResumeAt}을 보여 주고
     * 사용자가 다시 누르게 한다 — 자동 재개는 checkpoint와 재진입이 있어야 안전한데,
     * 그건 Phase 2의 몫이다.
     */
    RATE_LIMITED,

    /** 저장소 일부가 실패했지만 나머지는 끝났다. 부분 실패를 전체 실패로 만들지 않는다. */
    PARTIALLY_COMPLETED,

    COMPLETED,

    FAILED,

    CANCELLED;

    public boolean isTerminal() {
        return this == PARTIALLY_COMPLETED || this == COMPLETED || this == FAILED
                || this == CANCELLED;
    }
}
