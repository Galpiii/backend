package com.github.yeonja23.springboottemplate.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    INTERNAL_SERVER_ERROR("COMMON-001", HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
    INVALID_INPUT_VALUE("COMMON-002", HttpStatus.BAD_REQUEST, "요청 값이 유효하지 않습니다."),
    METHOD_NOT_ALLOWED("COMMON-003", HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 HTTP 메서드입니다."),
    RESOURCE_NOT_FOUND("COMMON-004", HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    INVALID_TYPE_VALUE("COMMON-005", HttpStatus.BAD_REQUEST, "요청 값의 타입이 올바르지 않습니다."),
    MISSING_REQUEST_PARAMETER("COMMON-006", HttpStatus.BAD_REQUEST, "필수 요청 파라미터가 누락되었습니다."),
    INVALID_REQUEST_BODY("COMMON-007", HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다."),
    CONFLICT("COMMON-008", HttpStatus.CONFLICT, "이미 존재하는 리소스입니다."),
    BAD_REQUEST("COMMON-009", HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),

    // Auth
    UNAUTHORIZED("AUTH-001", HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN("AUTH-002", HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    EXPIRED_TOKEN("AUTH-003", HttpStatus.UNAUTHORIZED, "만료된 토큰입니다."),
    INVALID_TOKEN("AUTH-004", HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    LOGIN_FAILED("AUTH-005", HttpStatus.UNAUTHORIZED, "로그인에 실패했습니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
