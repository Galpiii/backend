package com.github.galpiii.galpi.domain.github.exception;

import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;

public class GithubReauthRequiredException extends GlobalException {

    public GithubReauthRequiredException() {
        super(ErrorCode.GITHUB_REAUTH_REQUIRED);
    }
}
