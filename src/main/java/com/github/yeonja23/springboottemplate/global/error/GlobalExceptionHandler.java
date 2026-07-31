package com.github.yeonja23.springboottemplate.global.error;

import com.github.yeonja23.springboottemplate.global.error.exception.GlobalException;
import com.github.yeonja23.springboottemplate.global.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 커스텀 예외
    @ExceptionHandler(GlobalException.class)
    public ResponseEntity<ApiResponse<Void>> handleGlobalException(GlobalException e) {
        log.warn("[GlobalException] code: {}, message: {}", e.getErrorCode().getCode(), e.getMessage());
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode.getCode(), errorCode.getMessage()));
    }

    // @Valid 유효성 검사 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        log.warn("[MethodArgumentNotValidException] message: {}", message);
        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT_VALUE.getStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE.getCode(), message));
    }

    // 허용되지 않은 HTTP 메서드
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        log.warn("[HttpRequestMethodNotSupportedException] message: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED.getCode(), ErrorCode.METHOD_NOT_ALLOWED.getMessage()));
    }

    // 존재하지 않는 URL
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("[NoResourceFoundException] message: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.RESOURCE_NOT_FOUND.getStatus())
                .body(ApiResponse.error(ErrorCode.RESOURCE_NOT_FOUND.getCode(), ErrorCode.RESOURCE_NOT_FOUND.getMessage()));
    }

    // 요청 파라미터 타입 불일치
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        log.warn("[MethodArgumentTypeMismatchException] message: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.INVALID_TYPE_VALUE.getStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_TYPE_VALUE.getCode(), ErrorCode.INVALID_TYPE_VALUE.getMessage()));
    }

    // 필수 요청 파라미터 누락
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        log.warn("[MissingServletRequestParameterException] message: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.MISSING_REQUEST_PARAMETER.getStatus())
                .body(ApiResponse.error(ErrorCode.MISSING_REQUEST_PARAMETER.getCode(), ErrorCode.MISSING_REQUEST_PARAMETER.getMessage()));
    }

    // 요청 본문 파싱 실패
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("[HttpMessageNotReadableException] message: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.INVALID_REQUEST_BODY.getStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_REQUEST_BODY.getCode(), ErrorCode.INVALID_REQUEST_BODY.getMessage()));
    }

    // 그 외 모든 예외
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("[Exception] message: {}", e.getMessage(), e);
        return ResponseEntity
                .status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR.getCode(), ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
    }
}
