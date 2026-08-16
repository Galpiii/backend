package com.github.galpiii.galpi.domain.github.exception;

import com.github.galpiii.galpi.global.error.ErrorCode;

/** 목록 조회 뒤 삭제되는 경합 등으로 특정 installation만 더 이상 조회할 수 없을 때 사용한다. */
public class GithubInstallationUnavailableException extends GithubApiException {

    public GithubInstallationUnavailableException() {
        super(ErrorCode.GITHUB_INSTALLATION_UNAVAILABLE);
    }
}
