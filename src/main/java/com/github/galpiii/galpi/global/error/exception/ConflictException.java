package com.github.galpiii.galpi.global.error.exception;

import com.github.galpiii.galpi.global.error.ErrorCode;

public class ConflictException extends GlobalException {

    public ConflictException() {
        super(ErrorCode.CONFLICT);
    }

    public ConflictException(ErrorCode errorCode) {
        super(errorCode);
    }
}
