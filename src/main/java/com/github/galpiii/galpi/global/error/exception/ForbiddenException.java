package com.github.galpiii.galpi.global.error.exception;

import com.github.galpiii.galpi.global.error.ErrorCode;

public class ForbiddenException extends GlobalException {

    public ForbiddenException() {
        super(ErrorCode.FORBIDDEN);
    }

    public ForbiddenException(ErrorCode errorCode) {
        super(errorCode);
    }
}
