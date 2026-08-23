package com.github.galpiii.galpi.domain.collection.archive;

import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;

/**
 * tarball에 안전하게 풀 수 없는 엔트리가 있다.
 *
 * <p>경로 탈출·symlink·특수 파일은 저장소가 실수로 만들 수 있는 것이 아니다. 해당 엔트리만
 * 건너뛰지 않고 저장소 전체를 실패시키는 이유가 그것이다 — 공격으로 봐야 하는 입력이다.
 */
public class UnsafeArchiveEntryException extends GlobalException {

    public UnsafeArchiveEntryException() {
        super(ErrorCode.COLLECTION_ARCHIVE_UNSAFE_ENTRY);
    }
}
