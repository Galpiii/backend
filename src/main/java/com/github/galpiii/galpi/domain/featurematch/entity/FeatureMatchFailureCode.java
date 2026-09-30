package com.github.galpiii.galpi.domain.featurematch.entity;


public enum FeatureMatchFailureCode {
    /**
     * AI 제공자 호출 실패.
     */
    AI_CALL_FAILED,
    /**
     * AI 호출 시간 예산 초과.
     */
    AI_TIMEOUT,
    /**
     * DB 오류 또는 내부 처리 오류.
     */
    INTERNAL_ERROR,
    /**
     * 사용할 수 없는 AI 응답.
     */
    AI_RESPONSE_INVALID,
    /**
     * 생략할 수 없는 기능 목록이 입력 예산을 초과함.
     */
    INPUT_TOO_LARGE,
    /**
     * 실행 기준 명세서 또는 PR 분석이 변경됨.
     */
    SOURCE_CHANGED,
    /**
     * AI 분석 동의가 철회됨.
     */
    CONSENT_REVOKED,
    /**
     * 프로젝트가 삭제됨.
     */
    PROJECT_DELETED,
    /**
     * 저장소 연결이 해제됨.
     */
    REPOSITORY_UNLINKED,
    /**
     * GitHub 연결이 해제됨.
     */
    GITHUB_DISCONNECTED,
    /**
     * 대상의 최대 실행 횟수를 초과함.
     */
    MAX_ATTEMPTS_EXCEEDED
}
