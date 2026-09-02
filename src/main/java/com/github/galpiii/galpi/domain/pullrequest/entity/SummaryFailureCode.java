package com.github.galpiii.galpi.domain.pullrequest.entity;

/**
 * 요약이 실패한 이유. {@code pull_request_analyses.error_code}에 이름 그대로 들어간다.
 *
 * <p>사용자에게 나가는 {@code ErrorCode}와 별개다. 저쪽은 HTTP 상태로 번역되는 값이고,
 * 이쪽은 목록·상세 응답에 실려 화면이 "왜 이 PR만 분석하지 못했는지"를 설명하는 데 쓴다.
 * 요약 실패는 요청 실패가 아니므로 두 체계를 섞지 않는다.
 *
 * <p>rate limit에는 코드가 없다. 그건 실행 실패가 아니라 "아직 시작하지 못했다"이고,
 * 해제 시각까지 미루면서 시도 횟수도 되돌리므로 종료 상태에 닿지 않는다. 화면에는 실패가
 * 아니라 대기로 남는 것이 맞다.
 */
public enum SummaryFailureCode {

    /** LLM 호출이 재시도까지 실패했다. */
    SUMMARY_LLM_FAILED,

    /** LLM 호출이 예산 안에 끝나지 않았다. */
    SUMMARY_LLM_TIMEOUT,

    /** 응답이 왔지만 저장 규칙(길이·코드 스니펫·빈 문자열)을 만족하지 못했다. */
    SUMMARY_RESPONSE_INVALID,

    /** GitHub에서 변경 파일을 다시 받지 못했다. */
    PATCH_UNAVAILABLE,

    /** 저장소가 삭제됐거나 권한이 회수됐다. */
    REPOSITORY_INACCESSIBLE,

    /**
     * 큐에 들어간 뒤 외부 전송 동의가 사라졌다.
     *
     * <p>재시도로 풀리지 않는다. 사용자가 다시 동의한 뒤 재요약을 눌러야 한다.
     */
    CONSENT_REVOKED,

    /** 큐에 들어간 뒤 프로젝트가 삭제됐다. */
    PROJECT_DELETED,

    /** 큐에 들어간 뒤 저장소가 프로젝트에서 연결 해제됐다. */
    REPOSITORY_UNLINKED,

    /** 큐에 들어간 뒤 요청자가 GitHub 연결을 해제했다. */
    GITHUB_DISCONNECTED,

    /** 시도 상한을 넘겼다. 마지막 실패 사유를 덮어쓰지 않도록 다른 코드가 없을 때만 쓴다. */
    MAX_ATTEMPTS_EXCEEDED
}
