package com.github.galpiii.galpi.global.error.exception;

import com.github.galpiii.galpi.global.error.ErrorCode;
import lombok.Getter;

@Getter
public class GlobalException extends RuntimeException {

    private final ErrorCode errorCode;

    public GlobalException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
