package com.github.galpiii.galpi.domain.collection.archive;

import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;

/** tarball이 다운로드·해제 상한을 넘었다. 저장소 하나의 실패로만 다루고 작업 전체는 계속한다. */
public class ArchiveLimitExceededException extends GlobalException {

    public ArchiveLimitExceededException(ErrorCode errorCode) {
        super(errorCode);
    }
}
