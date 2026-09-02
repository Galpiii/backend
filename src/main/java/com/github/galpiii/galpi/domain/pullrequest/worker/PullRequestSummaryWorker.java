package com.github.galpiii.galpi.domain.pullrequest.worker;

import com.github.galpiii.galpi.domain.pullrequest.config.SummaryProperties;
import com.github.galpiii.galpi.global.util.Hashes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@code pull_request_analyses}를 폴링해 요약을 집어 실행한다.
 *
 * <p>큐 대신 DB를 쓰는 이유는 서버를 재시작해도 남아야 하기 때문이다. PENDING으로 남은 행은
 * 다음에 뜬 워커가 그대로 집어 간다. {@code AnalysisRunWorker}와 같은 구조이고, 다른 점은
 * 한 번에 여럿을 집는다는 것뿐이다 -- 요약 하나는 몇 초라 하나씩 집으면 폴링 주기가 그대로
 * 처리량 상한이 된다.
 *
 * <p>테스트에서는 {@code SchedulingConfig}가 {@code @EnableScheduling}을 끄기 때문에 이
 * 메서드가 저절로 돌지 않는다. 테스트는 필요한 시점에 {@link #runOnce()}를 직접 호출한다.
 */
@Slf4j
@Component
public class PullRequestSummaryWorker {

    private final PullRequestSummaryClaimer claimer;
    private final PullRequestSummaryExecutor executor;
    private final SummaryProperties properties;
    private final String workerId;

    public PullRequestSummaryWorker(PullRequestSummaryClaimer claimer,
                                    PullRequestSummaryExecutor executor,
                                    SummaryProperties properties) {
        this.claimer = claimer;
        this.executor = executor;
        this.properties = properties;
        this.workerId = buildWorkerId();
    }

    @Scheduled(fixedDelayString = "${galpi.summary.worker.poll-interval:5s}")
    public void poll() {
        if (!properties.worker().enabled()) {
            return;
        }
        runOnce();
    }

    /** 한 배치만 집어 실행한다. 테스트와 스케줄러가 함께 쓴다. */
    public boolean runOnce() {
        List<Long> claimed;
        try {
            claimed = claimer.claim(workerId);
        } catch (RuntimeException e) {
            // DB가 잠깐 흔들려도 폴링 자체가 멈추면 안 된다. 다음 주기에 다시 시도한다.
            log.warn("[요약] 선점에 실패했다 cause={}", e.getClass().getSimpleName());
            return false;
        }
        if (claimed.isEmpty()) {
            return false;
        }

        log.info("[요약] 배치를 선점했다 count={} worker={}", claimed.size(), workerId);
        ScheduledExecutorService heartbeatExecutor = newHeartbeatExecutor();
        long heartbeatMillis = heartbeatInterval(properties.worker().lease()).toMillis();
        heartbeatExecutor.scheduleWithFixedDelay(
                () -> heartbeat(claimed), heartbeatMillis, heartbeatMillis,
                TimeUnit.MILLISECONDS);
        try {
            executor.execute(claimed, workerId);
        } catch (RuntimeException e) {
            // 여기까지 올라온 예외는 실행기가 처리하지 못한 것이다. RUNNING 상태와 claimed_at을
            // 남겨 lease가 만료된 뒤 다른 워커가 이어받게 한다.
            log.error("[요약] 배치 실행이 예상치 못하게 끝났다 count={} cause={}",
                    claimed.size(), e.getClass().getSimpleName());
        } finally {
            heartbeatExecutor.shutdownNow();
        }
        return true;
    }

    /**
     * 배치 전체의 lease를 한 번에 연장한다.
     *
     * <p>배치 안에 이미 끝난 요약이 섞여 있어도 상관없다. 갱신 조건이 {@code RUNNING}이고
     * {@code claimed_by}가 이 워커인 행만 보므로, 끝난 행은 조건에서 자연히 빠진다.
     */
    private void heartbeat(List<Long> ids) {
        try {
            claimer.heartbeat(ids, workerId);
        } catch (RuntimeException e) {
            // 한 번 실패해도 다음 주기에 다시 갱신한다. DB 장애가 lease 전체보다 길면 다른
            // 워커가 회수할 수 있고, 그 경우 소유자 조건 때문에 이 워커의 갱신은 거부된다.
            log.warn("[요약] lease 갱신에 실패했다 cause={}", e.getClass().getSimpleName());
        }
    }

    private static Duration heartbeatInterval(Duration lease) {
        long millis = Math.max(1_000, lease.toMillis() / 3);
        return Duration.ofMillis(millis);
    }

    private static ScheduledExecutorService newHeartbeatExecutor() {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "pr-summary-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 워커 식별자. 호스트명만 쓰면 같은 호스트의 인스턴스 둘을 구분하지 못한다.
     *
     * <p>{@code claimed_by}는 진단용만이 아니다. 실행 쪽이 "내가 실제로 집은 행"을 이 값으로
     * 다시 걸러 내므로, 인스턴스마다 달라야 한다.
     */
    private static String buildWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        String id = host + "-" + Hashes.randomUrlSafe(6);
        return id.length() <= 100 ? id : id.substring(id.length() - 100);
    }
}
