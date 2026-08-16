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
    MISSING_REQUEST_VALUE("COMMON-006", HttpStatus.BAD_REQUEST, "필수 요청 값이 누락되었습니다."),
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
    REFRESH_TOKEN_REUSED("AUTH-008", HttpStatus.UNAUTHORIZED,
            "이미 사용된 리프레시 토큰이 다시 사용되어 모든 세션을 종료했습니다. 다시 로그인해 주세요."),
    SESSION_EXPIRED("AUTH-009", HttpStatus.UNAUTHORIZED, "세션 최대 유지 기간이 지났습니다. 다시 로그인해 주세요."),
    CSRF_HEADER_REQUIRED("AUTH-010", HttpStatus.FORBIDDEN, "이 요청에는 X-Galpi-Request 헤더가 필요합니다."),

    GITHUB_REAUTH_REQUIRED("GITHUB-001", HttpStatus.UNAUTHORIZED, "GitHub 재연결이 필요합니다."),
    GITHUB_OAUTH_STATE_INVALID("GITHUB-002", HttpStatus.BAD_REQUEST, "유효하지 않거나 만료된 state입니다."),
    GITHUB_OAUTH_CODE_REUSED("GITHUB-003", HttpStatus.BAD_REQUEST, "이미 사용된 인증 코드입니다."),
    GITHUB_OAUTH_FAILED("GITHUB-004", HttpStatus.UNAUTHORIZED, "GitHub 인증에 실패했습니다."),
    GITHUB_API_ERROR("GITHUB-005", HttpStatus.BAD_GATEWAY, "GitHub API 호출에 실패했습니다."),
    GITHUB_RATE_LIMITED("GITHUB-006", HttpStatus.TOO_MANY_REQUESTS, "GitHub API 호출 한도를 초과했습니다."),
    GITHUB_REDIRECT_NOT_ALLOWED("GITHUB-007", HttpStatus.BAD_REQUEST, "허용되지 않은 리다이렉트 대상입니다."),

    // Project
    PROJECT_NOT_FOUND("PROJECT-001", HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다."),
    PROJECT_NOT_ACCESSIBLE("PROJECT-002", HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없거나 접근할 수 없습니다."),

    // Feature Spec
    FEATURE_SPEC_FILE_EMPTY("FEATURE-SPEC-FILE-001", HttpStatus.BAD_REQUEST, "업로드된 파일이 비어 있습니다."),
    FEATURE_SPEC_FILE_NAME_MISSING("FEATURE-SPEC-FILE-002", HttpStatus.BAD_REQUEST, "원본 파일명이 존재하지 않습니다."),
    FEATURE_SPEC_FILE_EXTENSION_INVALID("FEATURE-SPEC-FILE-003", HttpStatus.BAD_REQUEST, "PDF 확장자 파일만 업로드할 수 있습니다."),
    FEATURE_SPEC_FILE_CONTENT_TYPE_INVALID("FEATURE-SPEC-FILE-004", HttpStatus.BAD_REQUEST, "파일의 Content-Type이 application/pdf가 아닙니다."),
    FEATURE_SPEC_FILE_SIZE_EXCEEDED("FEATURE-SPEC-FILE-005", HttpStatus.BAD_REQUEST, "파일 크기는 20MB를 초과할 수 없습니다."),
    FEATURE_SPEC_FILE_NAME_TOO_LONG("FEATURE-SPEC-FILE-006", HttpStatus.BAD_REQUEST, "파일명은 255자를 초과할 수 없습니다."),

    FEATURE_SPEC_PDF_INVALID("FEATURE-SPEC-PDF-001", HttpStatus.BAD_REQUEST, "정상적으로 열 수 있는 PDF 파일이 아닙니다."),
    FEATURE_SPEC_PDF_ENCRYPTED("FEATURE-SPEC-PDF-002", HttpStatus.BAD_REQUEST, "암호화되거나 비밀번호로 보호된 PDF는 업로드할 수 없습니다."),
    FEATURE_SPEC_PDF_PAGE_MISSING("FEATURE-SPEC-PDF-003", HttpStatus.BAD_REQUEST, "PDF 문서에는 한 페이지 이상이 필요합니다."),
    FEATURE_SPEC_PDF_PAGE_LIMIT_EXCEEDED("FEATURE-SPEC-PDF-004", HttpStatus.BAD_REQUEST, "PDF 문서는 100페이지를 초과할 수 없습니다."),

    FEATURE_SPEC_ALREADY_EXISTS("FEATURE-SPEC-EXISTS-001", HttpStatus.CONFLICT, "이미 등록된 기능명세서가 있습니다."),
    FEATURE_SPEC_STORAGE_UPLOAD_FAILED("FEATURE-SPEC-STORAGE-001", HttpStatus.INTERNAL_SERVER_ERROR, "기능명세서 파일 저장에 실패했습니다."),
    FEATURE_SPEC_SAVE_FAILED("FEATURE-SPEC-SAVE-001", HttpStatus.INTERNAL_SERVER_ERROR, "기능명세서 정보 저장에 실패했습니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
