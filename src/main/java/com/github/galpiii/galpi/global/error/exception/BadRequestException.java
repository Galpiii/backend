package com.github.galpiii.galpi.global.error.exception;

import com.github.galpiii.galpi.global.error.ErrorCode;

public class BadRequestException extends GlobalException {

    public BadRequestException() {
        super(ErrorCode.BAD_REQUEST);
    }

    public BadRequestException(ErrorCode errorCode) {
        super(errorCode);
    }
}
