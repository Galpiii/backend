package com.github.galpiii.galpi.ai.support;

/**
 * 재시도로 풀리지 않는 AI 호출 실패의 갈래.
 *
 * <p>메시지 문자열로 구분하지 않으려고 둔다. 워커는 이 값으로 화면에 나갈 실패 사유를 정하는데,
 * 문구를 다듬는 변경이 그 판단을 조용히 망가뜨리면 안 된다.
 */
public enum AiFailureKind {

    /** 예산이 끝나 더 시도하지 않았다. 사용자 입장에서는 시간 초과다. */
    BUDGET_EXHAUSTED,

    /** 재시도 상한까지 실패했다. */
    RETRIES_EXHAUSTED,

    /** 다시 걸어도 결과가 같은 실패다. 4xx가 여기 온다. */
    CALL_FAILED,

    /** 재시도 대기 중에 스레드가 인터럽트됐다. 종료 중일 가능성이 높다. */
    INTERRUPTED
}
