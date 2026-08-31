package com.github.galpiii.galpi.domain.pullrequest.entity;

/**
 * PR의 주된 변경 유형. LLM이 하나만 고른다.
 *
 * <p>한글 라벨("기능 추가")은 여기 두지 않는다. enum 이름 그대로 내려보내고 프론트가
 * 매핑한다 — {@code ProjectStatus}, {@code AnalysisRunStatus}가 이미 그렇게 하고 있고,
 * 서버가 표시 문구를 들고 있으면 화면 문구를 고칠 때마다 배포가 필요해진다.
 */
public enum ChangeType {

    /** 기능 추가 */
    FEATURE,

    /** 버그 수정 */
    BUGFIX,

    /** 리팩터링 */
    REFACTOR,

    /** 테스트 */
    TEST,

    /** 문서 */
    DOCS,

    /** 빌드·배포·설정 */
    INFRA,

    /** 포맷팅·린트 */
    STYLE,

    /** 되돌리기 */
    REVERT,

    /** 잡무 */
    CHORE,

    /** 위 어디에도 안 맞음 */
    OTHER
}
