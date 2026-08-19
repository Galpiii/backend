package com.github.galpiii.galpi.domain.collection.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 기본 인계 대상. 무엇을 넘겼는지 세어서 로그만 남기고 버린다.
 *
 * <p>기능 대조 파이프라인이 붙기 전까지 수집 경로를 끝까지 동작시키기 위한 자리다.
 * {@link ConditionalOnMissingBean}이라 실제 구현이 빈으로 등록되는 순간 조용히 물러난다.
 *
 * <p>로그에 담는 것은 개수와 바이트뿐이다. 경로도 내용도 찍지 않는다 — 비공개 저장소의 파일
 * 경로만으로도 내부 구조가 드러난다.
 */
@Slf4j
@Configuration
public class LoggingAnalysisPipeline {

    @Bean
    @ConditionalOnMissingBean(AnalysisPipelinePort.class)
    public AnalysisPipelinePort loggingAnalysisPipelinePort() {
        return snapshot -> log.info(
                "[수집] 파이프라인 인계 repositoryId={} commit={} files={} excluded={} "
                        + "pullRequests={} bytes={} completeness={}",
                snapshot.githubRepositoryId(),
                abbreviate(snapshot.commitSha()),
                snapshot.collectedFiles().size(),
                snapshot.excludedFiles().size(),
                snapshot.pullRequests().size(),
                snapshot.collectedBytes(),
                snapshot.dataCompleteness());
    }

    private static String abbreviate(String sha) {
        if (sha == null || sha.length() <= 7) {
            return sha;
        }
        return sha.substring(0, 7);
    }
}
