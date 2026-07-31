package com.github.yeonja23.springboottemplate.global.error.exception;

import com.github.yeonja23.springboottemplate.global.error.ErrorCode;

public class ForbiddenException extends GlobalException {

    public ForbiddenException() {
        super(ErrorCode.FORBIDDEN);
    }

    public ForbiddenException(ErrorCode errorCode) {
        super(errorCode);
    }
}
