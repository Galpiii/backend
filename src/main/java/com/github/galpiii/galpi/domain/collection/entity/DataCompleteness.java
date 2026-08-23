package com.github.galpiii.galpi.domain.collection.entity;

/**
 * 수집한 데이터가 온전한지.
 *
 * <p>{@code PARTIAL}은 실패가 아니다. GitHub이 큰 파일의 patch를 생략했거나 상한에 걸려
 * 일부를 못 가져온 상태다. 부분 실패를 전체 실패로 만들지 않는 대신, 이 값과
 * {@link IncompleteReason}으로 어디가 비었는지 남긴다.
 *
 * <p>불완전한 데이터를 근거로 쓴 기능 대조 결과는 그 사실이 화면에 보여야 한다.
 */
public enum DataCompleteness {

    COMPLETE,
    PARTIAL
}
