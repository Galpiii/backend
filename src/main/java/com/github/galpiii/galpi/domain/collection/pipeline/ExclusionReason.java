package com.github.galpiii.galpi.domain.collection.pipeline;

/**
 * 파일이 수집 대상에서 빠진 이유.
 *
 * <p>앞의 여섯 개는 인계 계약이 지정한 값 그대로다. 나머지는 사용자 경로 설정과 읽기 실패를
 * 다른 사유로 거짓 기록하지 않기 위해 추가했다.
 */
public enum ExclusionReason {

    /** 단일 파일 크기 상한 초과. */
    SIZE_LIMIT,

    /** 바이너리·미디어 파일. */
    BINARY,

    /** 의존성·빌드 산출물·lock 파일·VCS/IDE 디렉터리·생성된 코드. */
    DEPENDENCY,

    /** 경로 규칙이나 내용 스캔에서 비밀정보로 판단됐다. */
    SECRET_SUSPECTED,

    /** Git LFS 포인터. 내용이 아니라 참조 텍스트라 분석 가치가 없다. */
    LFS_POINTER,

    /** 저장소당 내용 총합 상한에 걸려 우선순위에서 밀렸다. */
    TOTAL_CONTENT_LIMIT,

    /** 사용자가 {@code analysis_configs.exclude_paths}로 직접 뺐다. */
    CONFIGURED_EXCLUDE,

    /** 사용자가 {@code analysis_configs.include_paths}로 지정한 범위 밖이다. */
    CONFIGURED_INCLUDE,

    /** 순회·스니핑·비밀정보 검사 중 읽지 못한 파일. */
    READ_FAILED
}
