package com.github.galpiii.galpi.domain.pullrequest.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * PR 요약 전용 스레드 풀.
 *
 * <p>기능명세서 추출 풀과 나눈다. 저쪽은 사용자가 방금 올린 문서 한 건을 수십 초 동안
 * 분석하는 일이고, 이쪽은 짧은 요약 수백 건을 배경에서 돌리는 일이다. 풀을 공유하면
 * 사용자가 기다리는 작업이 배경 작업 뒤에 줄을 선다.
 *
 * <p>큐 용량을 배치 크기에 맞춘다. 워커는 한 배치를 제출하고 그 배치가 끝날 때까지 기다리므로
 * 큐에 배치 하나 이상이 쌓일 일이 없고, 넉넉히 잡아 두면 거부 정책이 영영 동작하지 않는다.
 */
@Configuration
@RequiredArgsConstructor
public class SummaryWorkerConfig {

    public static final String EXECUTOR = "pullRequestSummaryExecutorPool";

    private final SummaryProperties properties;

    @Bean(EXECUTOR)
    public ThreadPoolTaskExecutor pullRequestSummaryExecutorPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.worker().maxConcurrency());
        executor.setMaxPoolSize(properties.worker().maxConcurrency());
        executor.setQueueCapacity(properties.worker().batchSize());
        executor.setThreadNamePrefix("pr-summary-");
        // 종료 시 진행 중인 LLM 호출을 기다리지 않는다. 끝내지 못한 요약은 RUNNING으로 남고
        // lease가 지나면 다른 워커가 이어받는다 -- 그 복구 경로가 이미 있으므로 배포를
        // 몇 분씩 붙잡을 이유가 없다.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
