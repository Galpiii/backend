package com.github.galpiii.galpi.domain.collection.pipeline;

/**
 * 수집 결과를 분석 파이프라인에 넘기는 지점.
 *
 * <p>이것은 어댑터가 아니라 포트다. 인계 계약을 맞출 기존 인터페이스가 코드베이스에 없어서
 * — 현재 있는 AI 코드는 명세서 PDF에서 기능을 뽑는 쪽이고 저장소 코드를 받는 자리가 아니다 —
 * 받는 쪽 모양을 여기서 먼저 정의한다. 실제 기능 대조 파이프라인이 생기면 이 인터페이스를
 * 구현해 끼우면 되고, 수집기는 바뀌지 않는다.
 *
 * <p>구현체는 {@link CollectedRepositorySnapshot}을 <b>이 호출이 끝날 때까지만</b> 쓸 수 있다.
 * 파일 참조는 임시 디렉터리를 가리키고 그 디렉터리는 호출이 끝난 뒤 지워지므로, 스냅샷을
 * 필드에 담아 두거나 다른 스레드로 넘기면 안 된다.
 */
public interface AnalysisPipelinePort {

    void accept(CollectedRepositorySnapshot snapshot);
}
