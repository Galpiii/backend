package com.github.galpiii.galpi.domain.pullrequest.dto;

/**
 * PR 작성자.
 *
 * <p>GitHub 계정이 삭제되면 PR 응답의 {@code user}가 비어 오고 {@code contributor_id}도
 * {@code null}이 된다. 그때 이 객체 자체를 {@code null}로 내리고 프론트가 "알 수 없음"을
 * 그린다 -- 빈 문자열을 채워 보내면 화면이 그것을 login으로 착각한다.
 */
public record PullRequestAuthorResponse(String login, String avatarUrl) {

    public static PullRequestAuthorResponse of(String login, String avatarUrl) {
        return login == null ? null : new PullRequestAuthorResponse(login, avatarUrl);
    }
}
