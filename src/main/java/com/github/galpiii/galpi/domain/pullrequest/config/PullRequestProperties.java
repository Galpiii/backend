package com.github.galpiii.galpi.domain.pullrequest.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * PR 조회 API의 상한.
 *
 * @param defaultPageSize 목록에서 size를 주지 않았을 때의 크기. 화면 한 눈에 들어오는 양이다
 * @param maxPageSize     클라이언트가 보낸 size를 여기서 자른다. 상한이 없으면 size=100000
 *                        하나로 프로젝트 전체 PR을 한 응답에 담게 된다
 * @param maxDetailFiles  상세 응답에 실을 변경 파일 수.
 *                        <p>PR 하나의 변경 파일은 GitHub 상한인 3,000개까지 갈 수 있고, 그
 *                        전부를 내리면 응답이 수 MB가 된다. 화면은 파일 목록을 "AuthController.java ·
 *                        AuthService.java"처럼 훑어보는 용도로만 쓰므로 앞쪽 몇백 개면 충분하다.
 *                        <p>자른 사실은 {@code filesTruncated}로 알린다. 조용히 자르면 화면이
 *                        200개짜리 PR과 3,000개짜리 PR을 구분하지 못한다. 전체 개수는
 *                        {@code stats.changedFilesCount}에 그대로 남으므로 두 값이 어긋나는
 *                        것으로도 읽을 수 있다
 * @param maxDetailCommits 상세 응답에 실을 커밋 수. 제한이 없으면 커밋 메시지 길이까지
 *                         곱해져 PR 하나가 응답 크기와 메모리를 무제한으로 쓸 수 있다
 */
@Validated
@ConfigurationProperties(prefix = "galpi.pull-request")
public record PullRequestProperties(
        @DefaultValue("20") @Min(1) int defaultPageSize,
        @DefaultValue("100") @Min(1) @Max(200) int maxPageSize,
        @DefaultValue("200") @Min(1) int maxDetailFiles,
        @DefaultValue("200") @Min(1) int maxDetailCommits
) {
}
