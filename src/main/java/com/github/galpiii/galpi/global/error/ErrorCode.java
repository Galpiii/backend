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

    // Github
    GITHUB_REAUTH_REQUIRED("GITHUB-001", HttpStatus.UNAUTHORIZED, "GitHub 재연결이 필요합니다."),
    GITHUB_OAUTH_STATE_INVALID("GITHUB-002", HttpStatus.BAD_REQUEST, "유효하지 않거나 만료된 state입니다."),
    GITHUB_OAUTH_CODE_REUSED("GITHUB-003", HttpStatus.BAD_REQUEST, "이미 사용된 인증 코드입니다."),
    GITHUB_OAUTH_FAILED("GITHUB-004", HttpStatus.UNAUTHORIZED, "GitHub 인증에 실패했습니다."),
    GITHUB_API_ERROR("GITHUB-005", HttpStatus.BAD_GATEWAY, "GitHub API 호출에 실패했습니다."),
    GITHUB_RATE_LIMITED("GITHUB-006", HttpStatus.TOO_MANY_REQUESTS, "GitHub API 호출 한도를 초과했습니다."),
    GITHUB_REDIRECT_NOT_ALLOWED("GITHUB-007", HttpStatus.BAD_REQUEST, "허용되지 않은 리다이렉트 대상입니다."),
    GITHUB_INSTALL_NOT_STARTED("GITHUB-008", HttpStatus.BAD_REQUEST, "설치를 시작한 기록이 없습니다."),
    // GITHUB-009는 비어 있다. 쓰이지 않던 GITHUB_INSTALLATION_NOT_VERIFIED가 있던 자리다.
    // 설치 미확인은 에러가 아니라 콜백 리다이렉트의 installation=unverified로 나가므로 코드가 필요 없다.
    GITHUB_REPOSITORY_ACCESS_DENIED("GITHUB-010", HttpStatus.FORBIDDEN, "접근 권한이 없는 저장소입니다."),
    GITHUB_REPOSITORY_LIST_INCOMPLETE("GITHUB-011", HttpStatus.BAD_GATEWAY,
            "GitHub 저장소 목록을 끝까지 읽지 못했습니다. 잠시 후 다시 시도해 주세요."),
    GITHUB_OPERATION_BUDGET_EXCEEDED("GITHUB-012", HttpStatus.SERVICE_UNAVAILABLE,
            "GitHub 조회 범위가 너무 큽니다. 요청 범위를 줄여 다시 시도해 주세요."),
    GITHUB_OPERATION_TIMEOUT("GITHUB-013", HttpStatus.GATEWAY_TIMEOUT,
            "GitHub 조회 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요."),
    GITHUB_OPERATION_IN_PROGRESS("GITHUB-014", HttpStatus.TOO_MANY_REQUESTS,
            "GitHub 조회가 이미 진행 중입니다. 완료된 뒤 다시 시도해 주세요."),
    GITHUB_INSTALLATION_UNAVAILABLE("GITHUB-015", HttpStatus.BAD_GATEWAY,
            "GitHub 설치 정보를 더 이상 조회할 수 없습니다."),
    GITHUB_REPOSITORY_UNAVAILABLE("GITHUB-016", HttpStatus.NOT_FOUND,
            "GitHub 저장소에 접근할 수 없습니다. 삭제되었거나 권한이 회수되었습니다."),

    // Analysis
    ANALYSIS_RUN_NOT_FOUND("ANALYSIS-001", HttpStatus.NOT_FOUND, "분석 작업을 찾을 수 없습니다."),
    ANALYSIS_NO_REPOSITORY("ANALYSIS-002", HttpStatus.BAD_REQUEST,
            "분석할 저장소가 없습니다. 프로젝트에 저장소를 먼저 연결해 주세요."),
    ANALYSIS_NO_ACCESSIBLE_REPOSITORY("ANALYSIS-003", HttpStatus.FORBIDDEN,
            "접근할 수 있는 저장소가 없습니다. GitHub 권한을 확인해 주세요."),
    ANALYSIS_ALREADY_RUNNING("ANALYSIS-004", HttpStatus.CONFLICT,
            "이미 진행 중인 분석이 있습니다. 완료된 뒤 다시 시도해 주세요."),

    // Collection
    COLLECTION_ARCHIVE_TOO_LARGE("COLLECTION-001", HttpStatus.UNPROCESSABLE_CONTENT,
            "저장소 아카이브가 허용 크기를 초과했습니다."),
    COLLECTION_ARCHIVE_UNSAFE_ENTRY("COLLECTION-002", HttpStatus.UNPROCESSABLE_CONTENT,
            "저장소 아카이브에 안전하게 처리할 수 없는 항목이 있습니다."),
    COLLECTION_ARCHIVE_DOWNLOAD_FAILED("COLLECTION-003", HttpStatus.BAD_GATEWAY,
            "저장소 아카이브를 내려받지 못했습니다."),
    COLLECTION_ARCHIVE_INVALID("COLLECTION-004", HttpStatus.UNPROCESSABLE_CONTENT,
            "저장소 아카이브를 읽을 수 없습니다."),

    // Project
    PROJECT_NOT_FOUND("PROJECT-001", HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다."),
    PROJECT_REPOSITORY_NOT_FOUND("PROJECT-002", HttpStatus.NOT_FOUND, "프로젝트에 연결된 저장소가 아닙니다."),
    PROJECT_REPOSITORY_ALREADY_LINKED("PROJECT-003", HttpStatus.CONFLICT, "이미 추가된 저장소입니다."),
    PROJECT_LIMIT_EXCEEDED("PROJECT-004", HttpStatus.CONFLICT,
            "만들 수 있는 프로젝트 수를 초과했습니다. 쓰지 않는 프로젝트를 정리해 주세요."),
    PROJECT_STATUS_TRANSITION_NOT_ALLOWED("PROJECT-005", HttpStatus.BAD_REQUEST,
            "허용되지 않은 상태 변경입니다."),
    PROJECT_UPDATE_EMPTY("PROJECT-006", HttpStatus.BAD_REQUEST, "변경할 값이 없습니다."),
    // 아래 둘은 URL로 저장소를 직접 추가하는 경로에서만 쓴다. 저장소가 없는 경우와 접근 권한이
    // 없는 경우를 구분하지 않는 것이 PROJECT-008의 요점이다 — 구분하면 URL만 넣어 보는 것으로
    // 비공개 저장소의 존재 여부를 알아낼 수 있다.
    PROJECT_REPOSITORY_URL_INVALID("PROJECT-007", HttpStatus.BAD_REQUEST,
            "GitHub 저장소 URL 형식이 아닙니다."),
    PROJECT_REPOSITORY_NOT_ACCESSIBLE("PROJECT-008", HttpStatus.NOT_FOUND,
            "저장소를 찾을 수 없거나 갈피에 접근 권한이 없습니다. "
                    + "GitHub에서 이 저장소를 갈피 앱에 허용했는지 확인해 주세요."),

    // Feature Spec File
    FEATURE_SPEC_FILE_EMPTY("FEATURE-SPEC-FILE-001", HttpStatus.BAD_REQUEST, "업로드된 파일이 비어 있습니다."),
    FEATURE_SPEC_FILE_NAME_MISSING("FEATURE-SPEC-FILE-002", HttpStatus.BAD_REQUEST, "원본 파일명이 존재하지 않습니다."),
    FEATURE_SPEC_FILE_EXTENSION_INVALID("FEATURE-SPEC-FILE-003", HttpStatus.BAD_REQUEST, "PDF 확장자 파일만 업로드할 수 있습니다."),
    FEATURE_SPEC_FILE_CONTENT_TYPE_INVALID("FEATURE-SPEC-FILE-004", HttpStatus.BAD_REQUEST, "파일의 Content-Type이 application/pdf가 아닙니다."),
    FEATURE_SPEC_FILE_SIZE_EXCEEDED("FEATURE-SPEC-FILE-005", HttpStatus.BAD_REQUEST, "파일 크기는 20MB를 초과할 수 없습니다."),
    FEATURE_SPEC_FILE_NAME_TOO_LONG("FEATURE-SPEC-FILE-006", HttpStatus.BAD_REQUEST, "파일명은 255자를 초과할 수 없습니다."),

    // Feature Spec PDF
    FEATURE_SPEC_PDF_INVALID("FEATURE-SPEC-PDF-001", HttpStatus.BAD_REQUEST, "정상적으로 열 수 있는 PDF 파일이 아닙니다."),
    FEATURE_SPEC_PDF_ENCRYPTED("FEATURE-SPEC-PDF-002", HttpStatus.BAD_REQUEST, "암호화되거나 비밀번호로 보호된 PDF는 업로드할 수 없습니다."),
    FEATURE_SPEC_PDF_PAGE_MISSING("FEATURE-SPEC-PDF-003", HttpStatus.BAD_REQUEST, "PDF 문서에는 한 페이지 이상이 필요합니다."),
    FEATURE_SPEC_PDF_PAGE_LIMIT_EXCEEDED("FEATURE-SPEC-PDF-004", HttpStatus.BAD_REQUEST, "PDF 문서는 100페이지를 초과할 수 없습니다."),

    // Feature Spec
    FEATURE_SPEC_ALREADY_EXISTS("FEATURE-SPEC-EXISTS-001", HttpStatus.CONFLICT, "이미 등록된 기능명세서가 있습니다."),
    FEATURE_SPEC_NOT_ACCESSIBLE("FEATURE-SPEC-ACCESS-001", HttpStatus.NOT_FOUND, "기능명세서를 찾을 수 없거나 접근할 수 없습니다."),
    FEATURE_SPEC_EXTRACTION_BUSY("FEATURE-SPEC-EXTRACTION-001", HttpStatus.SERVICE_UNAVAILABLE, "분석 요청이 많아 지금은 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."),

    // Feature Review
    FEATURE_NOT_ACCESSIBLE("FEATURE-REVIEW-001", HttpStatus.NOT_FOUND, "기능을 찾을 수 없거나 접근할 수 없습니다."),
    FEATURE_UPDATE_EMPTY("FEATURE-REVIEW-002", HttpStatus.BAD_REQUEST, "변경할 값이 없습니다."),
    FEATURE_REQUIREMENT_NOT_OWNED("FEATURE-REVIEW-003", HttpStatus.BAD_REQUEST, "이 기능의 세부 요구사항이 아닙니다."),
    FEATURE_MERGE_NOT_ALLOWED("FEATURE-REVIEW-004", HttpStatus.BAD_REQUEST, "중복으로 판단된 기능이 아닙니다."),
    FEATURE_SPLIT_NOT_ALLOWED("FEATURE-REVIEW-005", HttpStatus.BAD_REQUEST, "적용할 수 있는 분리 추천안이 아닙니다."),
    FEATURE_REVIEW_CONFLICT("FEATURE-REVIEW-006", HttpStatus.CONFLICT, "다른 요청이 먼저 처리되어 대상이 변경되었습니다. 목록을 새로고침해 주세요."),
    FEATURE_REQUIREMENT_DUPLICATED("FEATURE-REVIEW-007", HttpStatus.BAD_REQUEST, "같은 세부 요구사항을 두 번 보냈습니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
