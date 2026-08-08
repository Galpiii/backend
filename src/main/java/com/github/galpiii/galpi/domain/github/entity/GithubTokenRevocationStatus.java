package com.github.galpiii.galpi.domain.github.entity;

public enum GithubTokenRevocationStatus {

    /** 아직 폐기를 재시도할 대상. */
    PENDING,

    /**
     * 자동 재시도를 멈춘 상태. 행은 남는다.
     *
     * <p>암호문을 지우면 GitHub에 살아 있는 토큰을 다시는 회수할 수 없다. 재시도를 포기하는
     * 것과 회수를 포기하는 것은 다르므로, 사유만 남기고 데이터는 보존한다.
     */
    DEAD
}
