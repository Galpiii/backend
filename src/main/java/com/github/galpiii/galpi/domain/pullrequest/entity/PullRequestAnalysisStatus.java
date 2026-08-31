package com.github.galpiii.galpi.domain.pullrequest.entity;

/**
 * PR 요약 하나의 진행 상태.
 *
 * <p>결과 상태이면서 큐 상태다. 워커가 {@code PENDING}을 집어 {@code RUNNING}으로 바꾸고,
 * 끝나면 {@code COMPLETED} 또는 {@code FAILED}가 된다. 실행 근거가 사라지면
 * {@code CANCELLED}로 접는다. 재요약은 {@code FAILED}를 다시
 * {@code PENDING}으로 되돌리는 것이라 상태가 한 방향으로만 흐르지는 않는다.
 */
public enum PullRequestAnalysisStatus {

    /** 큐에 들어 있다. 워커가 아직 집지 않았거나, 실패 후 재시도를 기다린다. */
    PENDING,

    /** 워커가 선점해 처리 중이다. lease가 지나면 다른 워커가 가져갈 수 있다. */
    RUNNING,

    /** 요약과 변경 유형이 채워졌다. */
    COMPLETED,

    /** 시도 상한까지 실패했다. {@code error_code}에 이유가 남는다. */
    FAILED,

    /** 프로젝트 삭제·저장소 해제·GitHub 연결 해제로 실행 근거가 사라졌다. */
    CANCELLED
}
