package com.github.galpiii.galpi.domain.collection.entity;

/** 수집이 온전하지 못한 이유. {@code pull_requests}와 {@code analysis_run_repositories}가 함께 쓴다. */
public enum IncompleteReason {

    /** GitHub이 큰 파일·바이너리의 patch를 응답에서 생략했다. */
    PATCH_OMITTED,

    /** PR 변경 파일이 GitHub의 3,000개 상한을 넘었거나, 압축 해제가 파일 개수 상한에 걸렸다. */
    FILE_LIMIT_EXCEEDED,

    /** 바이너리 파일이라 내용을 전달하지 못했다. */
    BINARY,

    /** 저장소의 병합 PR이 {@code analysis_configs.pr_limit}을 넘어 최신순으로 잘렸다. */
    PR_LIMIT_EXCEEDED,

    /** 수집 내용 총합이 저장소당 상한에 걸려 우선순위가 낮은 파일이 빠졌다. */
    TOTAL_CONTENT_LIMIT,

    /** 압축 해제 총 용량 상한에 걸려 뒤쪽 엔트리를 풀지 못했다. */
    ARCHIVE_SIZE_LIMIT,

    /** rate limit에 걸려 수집을 중단했다. 사용자가 다시 시도해야 한다. */
    RATE_LIMITED,

    /** 일부 PR을 가져오지 못했지만 나머지는 수집했다. */
    PR_COLLECTION_PARTIAL,

    /** PR 커밋 페이지 상한에 걸려 일부 커밋을 가져오지 못했다. */
    COMMIT_LIMIT_EXCEEDED,

    /** PR 텍스트와 patch가 저장소별 파이프라인 인계 상한을 넘었다. */
    PR_CONTENT_LIMIT,

    /** 저장소별 PR API 요청 budget이 소진돼 뒤쪽 PR을 가져오지 못했다. */
    PR_REQUEST_LIMIT,

    /** 분석 파이프라인으로 넘기는 PR 텍스트에서 비밀정보를 마스킹했다. */
    SECRET_REDACTED,

    /** 저장소 파일을 읽지 못해 일부 내용이 빠졌다. */
    FILE_READ_FAILED
}
