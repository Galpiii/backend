package com.github.galpiii.galpi.domain.github.event;

/**
 * 사용자가 GitHub 연결을 끊었다.
 *
 * <p>연결 해제의 파급은 GitHub 도메인 밖에도 있다 — 진행 중인 분석은 접근 권한을 근거로
 * 시작된 것이라 근거가 사라진 순간 멈춰야 한다. 그 처리를 여기서 직접 부르지 않고 이벤트로
 * 넘기는 것은 GitHub 도메인이 분석 도메인을 알게 되는 것을 막기 위해서다.
 *
 * <p>구독자는 동기로 실행된다. 취소가 실패했는데 연결 해제만 성공하면, 권한이 없는 상태로
 * 수집이 계속 돌아간다.
 */
public record GithubDisconnectedEvent(Long userId) {
}
