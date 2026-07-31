package com.github.galpiii.galpi.global.error.exception;

import com.github.galpiii.galpi.global.error.ErrorCode;

public class NotFoundException extends GlobalException {

    public NotFoundException() {
        super(ErrorCode.RESOURCE_NOT_FOUND);
    }

    public NotFoundException(ErrorCode errorCode) {
        super(errorCode);
    }
}
