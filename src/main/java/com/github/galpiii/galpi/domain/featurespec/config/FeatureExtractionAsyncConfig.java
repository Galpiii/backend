package com.github.galpiii.galpi.domain.featurespec.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 기능명세서 추출 전용 스레드 풀.
 *
 * <p>다른 비동기 작업과 풀을 공유하지 않는다. LLM 호출은 한 건이 수십 초를 차지해서, 공용 풀을
 * 쓰면 짧은 작업들이 추출 작업 뒤에 줄을 서게 된다.
 *
 * <p>거부 정책은 기본값인 AbortPolicy를 그대로 둔다. CallerRunsPolicy를 쓰면 업로드 요청 스레드가
 * LLM 분석을 대신 수행하게 되어 응답이 수십 초 동안 막힌다.
 */
@Configuration
@EnableAsync
@RequiredArgsConstructor
public class FeatureExtractionAsyncConfig {

    public static final String EXECUTOR = "featureExtractionExecutor";

    private final FeatureExtractionProperties properties;

    @Bean(EXECUTOR)
    public ThreadPoolTaskExecutor featureExtractionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.corePoolSize());
        executor.setMaxPoolSize(properties.maxPoolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("feature-extraction-");

        // 배포로 종료될 때 진행 중인 분석은 마무리할 시간을 준다. 대기 중이던 작업은 버려지고
        // StaleExtractionCleaner가 다음 기동 때 FAILED로 정리한다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) properties.awaitTermination().toSeconds());

        executor.initialize();

        return executor;
    }
}
