package com.github.galpiii.galpi.domain.github.dto;

/**
 * installation 하나를 읽지 못한 이유.
 *
 * <p>사용자가 할 일이 다르기 때문에 구분한다 — 정지는 GitHub에서 설치를 복구해야 하고,
 * 사라진 설치는 다시 설치해야 하며, 권한 문제는 조직 관리자에게 요청해야 한다.
 */
public enum InstallationFailureReason {

    /** GitHub이 설치를 정지시켰다. 저장소 API를 부르지도 않는다. */
    SUSPENDED,

    /** 목록을 읽은 뒤 설치가 사라졌거나 접근할 수 없게 됐다. */
    NOT_FOUND,

    /** GitHub이 이 설치의 저장소 조회를 거부했다. 조직 관리자에게 요청해야 하는 경우다. */
    FORBIDDEN,

    /**
     * GitHub 장애나 네트워크 오류로 못 읽었다. 사용자가 할 일은 다시 시도하는 것뿐이다.
     *
     * <p>스펙 §5.4의 표에는 없는 값이다. 표의 세 값으로는 5xx·타임아웃을 표현할 자리가
     * {@link #FORBIDDEN}밖에 없는데, 그러면 잠시 뒤 새로고침하면 될 사용자에게 "조직에서
     * 권한을 받으라"고 안내하게 된다.
     */
    TEMPORARY_ERROR
}
