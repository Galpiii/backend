package com.github.galpiii.galpi.domain.collection.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 저장소 하나를 수집할 때의 상한 (공통 문서 §5.3).
 *
 * <p>숫자를 설정으로 뺐지만 기본값이 곧 정책이다. 낮추는 것은 안전하고, 올리는 것은 워커
 * 메모리와 디스크를 그만큼 더 쓰겠다는 뜻이다.
 */
@Validated
@ConfigurationProperties(prefix = "galpi.collection")
public record CollectionProperties(

        /** 압축된 tarball 다운로드 상한. Content-Length가 아니라 실제 읽은 바이트로 잰다. */
        @DefaultValue("200MB") @NotNull DataSize maxDownloadSize,

        /** 압축 해제 총 용량. 압축 폭탄 방어의 본체다. */
        @DefaultValue("1GB") @NotNull DataSize maxExtractedSize,

        /** 압축 해제 파일 개수 상한. */
        @DefaultValue("20000") @Min(1) int maxEntryCount,

        /** 단일 파일 크기 상한. 넘으면 해제하지 않고 SIZE_LIMIT으로 기록한다. */
        @DefaultValue("1MB") @NotNull DataSize maxFileSize,

        /** 저장소당 수집 내용 총합. 우선순위가 낮은 파일부터 잘린다. */
        @DefaultValue("20MB") @NotNull DataSize maxTotalContentSize,

        /** 저장소당 PR 제목·본문·커밋 메시지·patch를 파이프라인에 유지할 총량. */
        @DefaultValue("20MB") @NotNull DataSize maxPullRequestContentSize,

        /** tarball 다운로드에서 따라갈 리다이렉트 횟수. */
        @DefaultValue("3") @Min(0) int maxRedirects,

        /** tarball 다운로드 요청 타임아웃. 200MB를 받을 수 있어야 하므로 API 호출보다 길다. */
        @DefaultValue("120s") @NotNull Duration downloadTimeout,

        /**
         * {@code /pulls/{n}/files} 페이지 상한.
         *
         * <p>GitHub이 PR당 3,000개까지만 주고 페이지당 100개라 30이면 그 끝까지다.
         */
        @DefaultValue("30") @Min(1) int maxPullRequestFilePages,

        /** {@code /pulls/{n}/commits} 페이지 상한. */
        @DefaultValue("30") @Min(1) int maxPullRequestCommitPages,

        /** PR 목록 페이지 상한. 병합 PR을 찾으려면 closed 목록을 더 훑어야 할 수 있다. */
        @DefaultValue("30") @Min(1) int maxPullRequestListPages,

        /** 저장소 하나의 PR 수집에 허용할 GitHub API 요청 총량(목록 30 + PR 300 × 3). */
        @DefaultValue("930") @Min(3) int maxPullRequestApiRequests
) {

    public long maxDownloadBytes() {
        return maxDownloadSize.toBytes();
    }

    public long maxExtractedBytes() {
        return maxExtractedSize.toBytes();
    }

    public long maxFileBytes() {
        return maxFileSize.toBytes();
    }

    public long maxTotalContentBytes() {
        return maxTotalContentSize.toBytes();
    }

    public long maxPullRequestContentBytes() {
        return maxPullRequestContentSize.toBytes();
    }
}
