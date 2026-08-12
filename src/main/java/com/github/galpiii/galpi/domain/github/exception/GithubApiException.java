package com.github.galpiii.galpi.domain.github.exception;

import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;

public class GithubApiException extends GlobalException {

    public GithubApiException(ErrorCode errorCode) {
        super(errorCode);
    }

    public GithubApiException() {
        super(ErrorCode.GITHUB_API_ERROR);
    }
}
