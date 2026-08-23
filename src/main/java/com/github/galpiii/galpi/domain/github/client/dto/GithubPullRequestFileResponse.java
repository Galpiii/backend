package com.github.galpiii.galpi.domain.github.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PR 변경 파일 응답.
 *
 * <p>{@code patch}는 <b>이 DTO와 인계 스냅샷에만 존재한다.</b> 엔티티에는 대응 컬럼이 없고,
 * 만들어서도 안 된다. 큰 파일과 바이너리는 GitHub이 이 필드를 아예 주지 않는데, 그 사실은
 * {@code patch == null}로만 알 수 있다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubPullRequestFileResponse(
        @JsonProperty("filename") String filename,
        @JsonProperty("previous_filename") String previousFilename,
        @JsonProperty("status") String status,
        @JsonProperty("additions") Integer additions,
        @JsonProperty("deletions") Integer deletions,
        @JsonProperty("changes") Integer changes,
        @JsonProperty("patch") String patch
) {

    public boolean isPatchOmitted() {
        return patch == null || patch.isEmpty();
    }
}
