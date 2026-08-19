package com.github.galpiii.galpi.domain.github.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * PR 커밋 응답.
 *
 * <p>커밋의 작성자는 두 군데에 있다. {@code commit.author}는 git이 기록한 이름과 시각이고,
 * 최상위 {@code author}는 그 이메일로 매칭된 GitHub 계정이다. 매칭이 안 되면 후자가 없다 —
 * 사내 이메일로 커밋하고 GitHub에 등록하지 않은 흔한 경우다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubCommitResponse(
        @JsonProperty("sha") String sha,
        @JsonProperty("commit") Commit commit,
        @JsonProperty("author") GithubUserResponse author
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Commit(
            @JsonProperty("message") String message,
            @JsonProperty("author") GitIdentity author
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitIdentity(
            @JsonProperty("name") String name,
            @JsonProperty("date") OffsetDateTime date
    ) {
    }

    public String message() {
        return commit == null ? null : commit.message();
    }

    public OffsetDateTime authoredAt() {
        if (commit == null || commit.author() == null) {
            return null;
        }
        return commit.author().date();
    }

    public String authorLogin() {
        if (author != null && author.login() != null) {
            return author.login();
        }
        // GitHub 계정에 매칭되지 않으면 git이 기록한 이름만 남는다. 표시용으로는 그게 낫다.
        return commit == null || commit.author() == null ? null : commit.author().name();
    }

    public Long authorGithubId() {
        return author == null ? null : author.id();
    }
}
