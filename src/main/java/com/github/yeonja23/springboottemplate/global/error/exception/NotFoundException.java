package com.github.yeonja23.springboottemplate.global.error.exception;

import com.github.yeonja23.springboottemplate.global.error.ErrorCode;

public class NotFoundException extends GlobalException {

    public NotFoundException() {
        super(ErrorCode.RESOURCE_NOT_FOUND);
    }

    public NotFoundException(ErrorCode errorCode) {
        super(errorCode);
    }
}
