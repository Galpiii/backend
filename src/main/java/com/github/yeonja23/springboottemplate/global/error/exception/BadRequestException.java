package com.github.yeonja23.springboottemplate.global.error.exception;

import com.github.yeonja23.springboottemplate.global.error.ErrorCode;

public class BadRequestException extends GlobalException {

    public BadRequestException() {
        super(ErrorCode.BAD_REQUEST);
    }

    public BadRequestException(ErrorCode errorCode) {
        super(errorCode);
    }
}
