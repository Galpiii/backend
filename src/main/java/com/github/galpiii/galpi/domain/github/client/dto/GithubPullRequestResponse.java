package com.github.galpiii.galpi.domain.github.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * PR 목록·상세 응답.
 *
 * <p>{@code user}가 {@code null}일 수 있다. GitHub 계정이 삭제되면 작성자 정보가 사라지는데,
 * 그 PR을 버리면 기능 구현 근거가 함께 사라지므로 작성자만 비우고 수집한다.
 *
 * <p>{@code changedFiles}·{@code additions}·{@code deletions}는 목록 응답에는 없고 상세 응답에만
 * 있다. 목록으로 받은 값을 그대로 저장하면 전부 0이 된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubPullRequestResponse(
        @JsonProperty("id") Long id,
        @JsonProperty("number") Integer number,
        @JsonProperty("title") String title,
        @JsonProperty("body") String body,
        @JsonProperty("state") String state,
        @JsonProperty("draft") Boolean draft,
        @JsonProperty("merged_at") OffsetDateTime mergedAt,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("merge_commit_sha") String mergeCommitSha,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("changed_files") Integer changedFiles,
        @JsonProperty("additions") Integer additions,
        @JsonProperty("deletions") Integer deletions,
        @JsonProperty("base") Ref base,
        @JsonProperty("head") Ref head,
        @JsonProperty("user") GithubUserResponse user
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ref(
            @JsonProperty("ref") String ref,
            @JsonProperty("sha") String sha
    ) {
    }

    public boolean isMerged() {
        return mergedAt != null;
    }

    public String baseRef() {
        return base == null ? null : base.ref();
    }

    public String baseSha() {
        return base == null ? null : base.sha();
    }

    public String headRef() {
        return head == null ? null : head.ref();
    }

    public String headSha() {
        return head == null ? null : head.sha();
    }
}
