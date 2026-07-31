package com.github.yeonja23.springboottemplate.global.error.exception;

import com.github.yeonja23.springboottemplate.global.error.ErrorCode;

public class UnauthorizedException extends GlobalException {

    public UnauthorizedException() {
        super(ErrorCode.UNAUTHORIZED);
    }

    public UnauthorizedException(ErrorCode errorCode) {
        super(errorCode);
    }
}
