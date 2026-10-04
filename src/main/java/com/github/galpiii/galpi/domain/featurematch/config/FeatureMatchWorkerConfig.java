package com.github.galpiii.galpi.domain.featurematch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

@Configuration
public class FeatureMatchWorkerConfig {

    @Bean(name = "featureMatchHeartbeatExecutor", destroyMethod = "shutdownNow")
    public ScheduledExecutorService featureMatchHeartbeatExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "feature-match-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    @Bean("featureMatchExecutor")
    public ThreadPoolTaskExecutor featureMatchExecutor(FeatureMatchProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.maxConcurrency());
        executor.setMaxPoolSize(properties.maxConcurrency());
        executor.setQueueCapacity(properties.maxConcurrency());
        executor.setThreadNamePrefix("feature-match-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
