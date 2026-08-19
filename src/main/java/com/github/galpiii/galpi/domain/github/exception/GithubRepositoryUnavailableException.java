package com.github.galpiii.galpi.domain.github.exception;

import com.github.galpiii.galpi.global.error.ErrorCode;

/**
 * 저장소 하나에 접근할 수 없다. 삭제됐거나 설치 범위에서 빠졌다.
 *
 * <p>설치 전체가 죽은 {@link GithubInstallationUnavailableException}과 구분한다. 이쪽은
 * 저장소 하나만 실패로 끝내고 같은 설치의 다른 저장소는 계속 진행할 수 있다.
 */
public class GithubRepositoryUnavailableException extends GithubApiException {

    public GithubRepositoryUnavailableException() {
        super(ErrorCode.GITHUB_REPOSITORY_UNAVAILABLE);
    }
}
