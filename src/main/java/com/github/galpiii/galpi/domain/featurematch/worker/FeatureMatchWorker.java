package com.github.galpiii.galpi.domain.featurematch.worker;

import com.github.galpiii.galpi.domain.featurematch.config.FeatureMatchProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class FeatureMatchWorker {

    private final FeatureMatchReconciler reconciler;
    private final FeatureMatchClaimer claimer;
    private final FeatureMatchRunExecutor matchExecutor;
    private final FeatureMatchProperties properties;
    private final Executor executor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Map<String, ScheduledFuture<?>> inFlight = new ConcurrentHashMap<>();
    private final String instanceId = instanceId();
    private boolean stopping;

    public FeatureMatchWorker(FeatureMatchReconciler reconciler, FeatureMatchClaimer claimer,
                              FeatureMatchRunExecutor matchExecutor, FeatureMatchProperties properties,
                              @Qualifier("featureMatchExecutor") Executor executor,
                              @Qualifier("featureMatchHeartbeatExecutor") ScheduledExecutorService heartbeatExecutor) {
        this.reconciler = reconciler;
        this.claimer = claimer;
        this.matchExecutor = matchExecutor;
        this.properties = properties;
        this.executor = executor;
        this.heartbeatExecutor = heartbeatExecutor;
    }

    /**
     * 이 인스턴스의 빈 슬롯만 채우고 반환한다. 느린 PR이 다음 선점을 막지 않는다.
     * 폴링과 종료만 직렬화하고, AI 실행은 별도 스레드에서 계속한다.
     */
    @Scheduled(fixedDelayString = "${galpi.feature-match.poll-interval:5s}")
    public synchronized void runOnce() {
        if (!properties.enabled() || stopping) {
            return;
        }
        try {
            reconciler.reconcile();
            int available = properties.maxConcurrency() - inFlight.size();
            for (int index = 0; index < available; index++) {
                // 같은 PR을 다시 선점하더라도 이전 호출과 토큰을 공유하지 않는다.
                String token = instanceId + ":" + UUID.randomUUID();
                Optional<Long> targetId = claimer.claim(token);
                if (targetId.isEmpty()) {
                    break;
                }
                submit(targetId.get(), token);
            }
        } catch (RuntimeException exception) {
            log.error("[기능대조] 작업 선점 실패 type={}", exception.getClass().getSimpleName());
        }
    }

    private static String instanceId() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            return hostname.substring(0, Math.min(hostname.length(), 60));
        } catch (UnknownHostException exception) {
            return "unknown-host";
        }
    }

    private void submit(long targetId, String token) {
        ScheduledFuture<?> heartbeat = null;
        try {
            long interval = Math.max(100, properties.lease().toMillis() / 3);
            heartbeat = heartbeatExecutor.scheduleWithFixedDelay(
                    () -> heartbeat(targetId, token), interval, interval, TimeUnit.MILLISECONDS);
            inFlight.put(token, heartbeat);
            executor.execute(() -> execute(targetId, token));
        } catch (RuntimeException exception) {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            inFlight.remove(token);
            // 종료 중이거나 실행기 큐가 거절한 작업은 실제 실행되지 않았다.
            try {
                claimer.release(targetId, token);
            } catch (RuntimeException releaseException) {
                // DB가 끊겼다면 heartbeat를 멈춰 lease 만료 후 회수되도록 둔다.
                log.warn("[기능대조] 선점 반환 실패 targetId={} type={}",
                        targetId, releaseException.getClass().getSimpleName());
            }
            throw exception;
        }
    }

    private void execute(long targetId, String token) {
        try {
            matchExecutor.execute(targetId, token);
        } catch (RuntimeException exception) {
            log.error("[기능대조] 작업 실행 실패 targetId={} type={}",
                    targetId, exception.getClass().getSimpleName());
        } finally {
            ScheduledFuture<?> heartbeat = inFlight.remove(token);
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        }
    }

    private void heartbeat(long targetId, String token) {
        try {
            if (!claimer.heartbeat(targetId, token)) {
                // 이미 완료·취소되었거나 다른 워커가 회수했다. 결과 저장도 토큰 검사로 거부된다.
                ScheduledFuture<?> heartbeat = inFlight.get(token);
                if (heartbeat != null) {
                    heartbeat.cancel(false);
                }
            }
        } catch (RuntimeException exception) {
            log.warn("[기능대조] lease 갱신 실패 targetId={} type={}",
                    targetId, exception.getClass().getSimpleName());
        }
    }

    @PreDestroy
    public synchronized void stop() {
        stopping = true;
        inFlight.values().forEach(heartbeat -> heartbeat.cancel(false));
        // 진행 중인 작업을 즉시 PENDING으로 되돌리지 않는다. 종료 후 lease로 회수한다.
    }
}
