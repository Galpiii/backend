package com.github.galpiii.galpi.domain.github.exception;

import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;

/**
 * GitHub 호출 실패. 사용자에게는 모두 {@code GITHUB-005}로 나가지만, 부분 실패를 화면에
 * 표시할 때는 원인을 갈라야 한다.
 *
 * <p>{@code httpStatus}가 그 근거다. 응답을 받지 못한 경우(네트워크 오류·타임아웃)는
 * {@code null}이다. 이 값이 없으면 GitHub 5xx와 권한 거부가 같은 예외로 뭉쳐, 잠시 뒤 다시
 * 하면 될 사용자에게 "조직 관리자에게 권한을 요청하라"고 안내하게 된다.
 */
public class GithubApiException extends GlobalException {

    private final Integer httpStatus;

    public GithubApiException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public GithubApiException() {
        this(ErrorCode.GITHUB_API_ERROR, null);
    }

    public GithubApiException(ErrorCode errorCode, Integer httpStatus) {
        super(errorCode);
        this.httpStatus = httpStatus;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    /**
     * 잠시 뒤 다시 하면 될 실패인지.
     *
     * <p>응답을 아예 받지 못했거나 GitHub 쪽 5xx면 사용자가 할 수 있는 일이 없다. 그 밖의
     * 상태 코드는 권한이나 요청 자체의 문제로 본다.
     */
    public boolean isTemporary() {
        return httpStatus == null || httpStatus >= 500;
    }
}
