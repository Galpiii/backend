package com.github.galpiii.galpi.domain.collection.pipeline;

/**
 * 파일이 수집 대상에서 빠진 이유.
 *
 * <p>앞의 여섯 개는 인계 계약이 지정한 값 그대로다. {@link #CONFIGURED_EXCLUDE} 하나만
 * 더했다 — 계약에는 없지만 {@code analysis_configs.exclude_paths}로 사용자가 직접 뺀 파일에
 * 붙일 사유가 목록에 없었다. 이걸 {@link #DEPENDENCY}로 접으면 "라이브러리라서 빠졌다"고
 * 거짓말을 하게 된다.
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
    CONFIGURED_EXCLUDE
}
