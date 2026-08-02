package com.github.galpiii.galpi.global.error;

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
    LOGIN_FAILED("AUTH-005", HttpStatus.UNAUTHORIZED, "로그인에 실패했습니다."),
    REFRESH_TOKEN_NOT_FOUND("AUTH-006", HttpStatus.UNAUTHORIZED, "만료되었거나 이미 사용된 리프레시 토큰입니다."),
    INVALID_LOGIN_CODE("AUTH-007", HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 로그인 코드입니다."),

    // GitHub
    GITHUB_REAUTH_REQUIRED("GITHUB-001", HttpStatus.UNAUTHORIZED, "GitHub 재연결이 필요합니다."),
    GITHUB_OAUTH_STATE_INVALID("GITHUB-002", HttpStatus.BAD_REQUEST, "유효하지 않거나 만료된 state입니다."),
    GITHUB_OAUTH_CODE_REUSED("GITHUB-003", HttpStatus.BAD_REQUEST, "이미 사용된 인증 코드입니다."),
    GITHUB_OAUTH_FAILED("GITHUB-004", HttpStatus.UNAUTHORIZED, "GitHub 인증에 실패했습니다."),
    GITHUB_API_ERROR("GITHUB-005", HttpStatus.BAD_GATEWAY, "GitHub API 호출에 실패했습니다."),
    GITHUB_RATE_LIMITED("GITHUB-006", HttpStatus.TOO_MANY_REQUESTS, "GitHub API 호출 한도를 초과했습니다."),
    GITHUB_REDIRECT_NOT_ALLOWED("GITHUB-007", HttpStatus.BAD_REQUEST, "허용되지 않은 리다이렉트 대상입니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
