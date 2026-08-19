package com.github.galpiii.galpi.domain.collection.entity;

import java.util.Locale;

/**
 * PR 변경 파일의 변경 유형.
 *
 * <p>GitHub은 문서에 적힌 다섯 가지 외에 {@code changed}와 {@code unchanged}도 돌려준다.
 * ERD의 CHECK 제약에는 다섯 개만 있으므로 나머지는 {@code MODIFIED}로 접는다 — 둘 다
 * "내용이 바뀌었을 수 있는 기존 파일"이라 대조에서 구분할 일이 없다.
 */
public enum ChangeStatus {

    ADDED,
    MODIFIED,
    REMOVED,
    RENAMED,
    COPIED;

    public static ChangeStatus from(String githubStatus) {
        if (githubStatus == null) {
            return MODIFIED;
        }
        return switch (githubStatus.toLowerCase(Locale.ROOT)) {
            case "added" -> ADDED;
            case "removed" -> REMOVED;
            case "renamed" -> RENAMED;
            case "copied" -> COPIED;
            default -> MODIFIED;
        };
    }
}
