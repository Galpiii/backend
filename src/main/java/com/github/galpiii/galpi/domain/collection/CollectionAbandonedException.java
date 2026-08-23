package com.github.galpiii.galpi.domain.collection;

/**
 * 수집 도중 작업이 취소·삭제돼 인계를 포기했다.
 *
 * <p>실패가 아니다. 저장소 하나를 실패로 기록하는 대신 남은 진행을 접기 위한 신호라,
 * 호출자는 이 예외를 실패 집계에 넣지 않는다.
 */
public class CollectionAbandonedException extends RuntimeException {

    public CollectionAbandonedException() {
        super("수집 중 작업이 취소됐습니다.");
    }
}
