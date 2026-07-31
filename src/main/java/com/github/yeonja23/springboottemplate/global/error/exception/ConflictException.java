package com.github.yeonja23.springboottemplate.global.error.exception;

import com.github.yeonja23.springboottemplate.global.error.ErrorCode;

public class ConflictException extends GlobalException {

    public ConflictException() {
        super(ErrorCode.CONFLICT);
    }

    public ConflictException(ErrorCode errorCode) {
        super(errorCode);
    }
}
