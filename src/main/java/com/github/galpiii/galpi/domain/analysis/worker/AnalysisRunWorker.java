package com.github.galpiii.galpi.domain.analysis.worker;

import com.github.galpiii.galpi.domain.analysis.config.AnalysisWorkerProperties;
import com.github.galpiii.galpi.global.util.Hashes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@code analysis_runs}를 폴링해 작업을 집어 실행한다.
 *
 * <p>큐 대신 DB를 쓰는 이유는 서버를 재시작해도 작업이 남아야 하기 때문이다. QUEUED 상태로
 * 남은 행은 다음에 뜬 워커가 그대로 집어 간다.
 *
 * <p>한 번에 하나만 집는다. 병렬 실행은 저장소 수집이 디스크와 GitHub rate limit을 함께
 * 쓰기 때문에 이득보다 위험이 크다. 처리량이 필요하면 인스턴스를 늘리면 되고,
 * {@code SKIP LOCKED} 선점이 그때 중복 실행을 막는다.
 *
 * <p>테스트에서는 {@code SchedulingConfig}가 {@code @EnableScheduling}을 끄기 때문에 이
 * 메서드가 저절로 돌지 않는다. 테스트는 필요한 시점에 직접 호출한다.
 */
@Slf4j
@Component
public class AnalysisRunWorker {

    private final AnalysisRunClaimer claimer;
    private final AnalysisRunExecutor executor;
    private final AnalysisWorkerProperties properties;
    private final String workerId;

    public AnalysisRunWorker(AnalysisRunClaimer claimer, AnalysisRunExecutor executor,
                             AnalysisWorkerProperties properties) {
        this.claimer = claimer;
        this.executor = executor;
        this.properties = properties;
        this.workerId = buildWorkerId();
    }

    @Scheduled(fixedDelayString = "${galpi.analysis.worker.poll-interval:5s}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        runOnce();
    }

    /** 한 번만 집어 실행한다. 테스트와 스케줄러가 함께 쓴다. */
    public boolean runOnce() {
        Optional<Long> claimed;
        try {
            claimed = claimer.claim(workerId);
        } catch (RuntimeException e) {
            // DB가 잠깐 흔들려도 폴링 자체가 멈추면 안 된다. 다음 주기에 다시 시도한다.
            log.warn("[분석] 작업 선점에 실패했다 cause={}", e.getClass().getSimpleName());
            return false;
        }
        if (claimed.isEmpty()) {
            return false;
        }

        Long runId = claimed.get();
        log.info("[분석] 작업을 선점했다 runId={} worker={}", runId, workerId);
        ScheduledExecutorService heartbeatExecutor = newHeartbeatExecutor();
        long heartbeatMillis = heartbeatInterval(properties.lease()).toMillis();
        heartbeatExecutor.scheduleWithFixedDelay(
                () -> heartbeat(runId), heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        try {
            executor.execute(runId);
        } catch (RuntimeException e) {
            // 여기까지 올라온 예외는 실행기가 처리하지 못한 것이다. RUNNING 상태와 claimed_at을
            // 남겨 lease가 만료된 뒤 다른 워커가 이어받게 한다.
            log.error("[분석] 작업 실행이 예상치 못하게 끝났다 runId={} cause={}",
                    runId, e.getClass().getSimpleName());
        } finally {
            heartbeatExecutor.shutdownNow();
        }
        return true;
    }

    private void heartbeat(Long runId) {
        try {
            if (!claimer.heartbeat(runId, workerId)) {
                log.warn("[분석] lease를 연장하지 못했다 runId={} worker={}", runId, workerId);
            }
        } catch (RuntimeException e) {
            // 한 번 실패해도 다음 주기에 다시 갱신한다. DB 장애가 lease 전체보다 길면 다른
            // 워커가 회수할 수 있고, 그 경우 소유자 조건 때문에 이 워커의 갱신은 거부된다.
            log.warn("[분석] lease 갱신에 실패했다 runId={} cause={}",
                    runId, e.getClass().getSimpleName());
        }
    }

    private static Duration heartbeatInterval(Duration lease) {
        long millis = Math.max(1_000, lease.toMillis() / 3);
        return Duration.ofMillis(millis);
    }

    private static ScheduledExecutorService newHeartbeatExecutor() {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "analysis-run-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 워커 식별자. 호스트명만 쓰면 같은 호스트의 인스턴스 둘을 구분하지 못한다.
     *
     * <p>{@code claimed_by}는 진단용이다. 중복 실행을 막는 것은 이 값이 아니라 선점 쿼리의
     * 잠금이다.
     */
    private static String buildWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        String suffix = Hashes.randomUrlSafe(6);
        String id = host + "-" + suffix;
        return id.length() <= 100 ? id : id.substring(id.length() - 100);
    }
}
