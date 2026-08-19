package com.github.galpiii.galpi.domain.analysis.worker;

import com.github.galpiii.galpi.domain.analysis.config.AnalysisWorkerProperties;
import com.github.galpiii.galpi.global.util.Hashes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

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
        try {
            executor.execute(runId);
        } catch (RuntimeException e) {
            // 여기까지 올라온 예외는 실행기가 처리하지 못한 것이다. 작업이 RUNNING으로 남아
            // 영영 끝나지 않는 것을 막아야 한다.
            log.error("[분석] 작업 실행이 예상치 못하게 끝났다 runId={} cause={}",
                    runId, e.getClass().getSimpleName());
        }
        return true;
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
