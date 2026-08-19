package com.github.galpiii.galpi.domain.analysis.worker;

import com.github.galpiii.galpi.domain.analysis.config.AnalysisWorkerProperties;
import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 작업 하나를 원자적으로 선점한다.
 *
 * <p>선택과 갱신이 <b>한 트랜잭션</b> 안에 있어야 한다. {@code SELECT ... FOR UPDATE SKIP
 * LOCKED}가 잡은 잠금은 트랜잭션이 끝날 때 풀리므로, 조회만 하고 트랜잭션을 닫으면 잠금이
 * 사라져 두 워커가 같은 작업을 집을 수 있다. 이 클래스가 따로 있는 이유가 그 경계다 —
 * 실행 전체를 트랜잭션으로 감쌀 수는 없다. 분석은 몇 분씩 걸리고 GitHub 호출을 기다린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisRunClaimer {

    private final AnalysisRunRepository runRepository;
    private final AnalysisWorkerProperties properties;

    /**
     * @return 선점한 작업 id. 가져갈 것이 없으면 빈 값
     */
    @Transactional
    public Optional<Long> claim(String workerId) {
        OffsetDateTime leaseExpiredBefore = OffsetDateTime.now().minus(properties.lease());

        return runRepository.findClaimableId(leaseExpiredBefore)
                .filter(runId -> {
                    boolean claimed = runRepository.claim(
                            runId, workerId, leaseExpiredBefore) > 0;
                    if (!claimed) {
                        // 잠금이 있으니 거의 오지 않는 경로다. 왔다면 상태 전이 가정이 깨진 것이다.
                        log.warn("[분석] 선점 직후 상태가 달라져 건너뛴다 runId={}", runId);
                    }
                    return claimed;
                });
    }

    /** 긴 수집 중 정상 워커가 stale로 오인돼 다른 워커에게 재선점되지 않게 한다. */
    @Transactional
    public boolean heartbeat(Long runId, String workerId) {
        return runRepository.heartbeat(runId, workerId) > 0;
    }
}
