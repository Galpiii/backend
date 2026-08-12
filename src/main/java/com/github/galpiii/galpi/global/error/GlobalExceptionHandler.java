package com.github.galpiii.galpi.global.error;

import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.global.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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

    @ExceptionHandler(GlobalException.class)
    public ResponseEntity<ApiResponse<Void>> handleGlobalException(GlobalException e) {
        log.warn("[GlobalException] code: {}, message: {}", e.getErrorCode().getCode(), e.getMessage());
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode.getCode(), errorCode.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        log.warn("[MethodArgumentNotValidException] message: {}", message);
        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT_VALUE.getStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE.getCode(), message));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        log.warn("[HttpRequestMethodNotSupportedException] message: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED.getCode(), ErrorCode.METHOD_NOT_ALLOWED.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("[NoResourceFoundException] message: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.RESOURCE_NOT_FOUND.getStatus())
                .body(ApiResponse.error(ErrorCode.RESOURCE_NOT_FOUND.getCode(), ErrorCode.RESOURCE_NOT_FOUND.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        log.warn("[MethodArgumentTypeMismatchException] message: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.INVALID_TYPE_VALUE.getStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_TYPE_VALUE.getCode(), ErrorCode.INVALID_TYPE_VALUE.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        log.warn("[MissingServletRequestParameterException] message: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.MISSING_REQUEST_PARAMETER.getStatus())
                .body(ApiResponse.error(ErrorCode.MISSING_REQUEST_PARAMETER.getCode(), ErrorCode.MISSING_REQUEST_PARAMETER.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("[HttpMessageNotReadableException] message: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.INVALID_REQUEST_BODY.getStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_REQUEST_BODY.getCode(), ErrorCode.INVALID_REQUEST_BODY.getMessage()));
    }

    /**
     * DB 제약 위반이 500으로 새는 것을 막는 마지막 그물이다.
     *
     * <p>어떤 제약이 걸렸는지 아는 곳에서 자기 에러 코드로 바꾸는 것이 원칙이다. 저장소 연결은
     * {@code ProjectRepositoryLinkWriter}가 409로 바꾼다. 여기까지 온 것은 아직 그런 처리가
     * 없는 경로라는 뜻이라, 안전한 409로만 내보내고 원인은 로그로 남긴다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolationException(DataIntegrityViolationException e) {
        log.warn("[DataIntegrityViolationException] message: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity
                .status(ErrorCode.CONFLICT.getStatus())
                .body(ApiResponse.error(ErrorCode.CONFLICT.getCode(), ErrorCode.CONFLICT.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("[Exception] message: {}", e.getMessage(), e);
        return ResponseEntity
                .status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR.getCode(), ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
    }
}
