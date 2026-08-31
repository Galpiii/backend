package com.github.galpiii.galpi.domain.pullrequest.worker;

import com.github.galpiii.galpi.domain.pullrequest.config.SummaryProperties;
import com.github.galpiii.galpi.domain.pullrequest.repository.PullRequestAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 요약 여러 건을 원자적으로 선점한다.
 *
 * <p>선택과 갱신이 <b>한 트랜잭션</b> 안에 있어야 한다. {@code SELECT ... FOR UPDATE SKIP
 * LOCKED}가 잡은 잠금은 트랜잭션이 끝날 때 풀리므로, 조회만 하고 트랜잭션을 닫으면 잠금이
 * 사라져 두 워커가 같은 PR을 집을 수 있다. 이 클래스가 따로 있는 이유가 그 경계다 -- 실행
 * 전체를 트랜잭션으로 감쌀 수는 없다. 요약은 GitHub 응답과 LLM 응답을 기다린다.
 *
 * <p>{@code AnalysisRunClaimer}와 다른 점은 한 번에 여럿을 집는다는 것뿐이다. 저장소 수집은
 * 한 건이 몇 분이라 폴링 주기가 묻히지만, 요약은 한 건이 몇 초라 하나씩 집으면 폴링 주기가
 * 그대로 처리량 상한이 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PullRequestSummaryClaimer {

    private final PullRequestAnalysisRepository analysisRepository;
    private final SummaryProperties properties;

    /**
     * @return 선점한 요약 id. 가져갈 것이 없으면 빈 목록
     */
    @Transactional
    public List<Long> claim(String workerId) {
        OffsetDateTime leaseExpiredBefore =
                OffsetDateTime.now().minus(properties.worker().lease());

        List<Long> ids = analysisRepository.findClaimableIds(
                leaseExpiredBefore, properties.worker().batchSize());
        if (ids.isEmpty()) {
            return List.of();
        }

        int claimed = analysisRepository.claim(ids, workerId, leaseExpiredBefore);
        if (claimed != ids.size()) {
            // 잠금이 있으니 거의 오지 않는 경로다. 왔다면 상태 전이 가정이 깨진 것이다.
            // 실제로 무엇을 집었는지는 실행 쪽이 소유자 조건으로 다시 걸러 낸다.
            log.warn("[요약] 선점 직후 상태가 달라진 행이 있다 selected={} claimed={}",
                    ids.size(), claimed);
        }
        return ids;
    }

    /** 긴 LLM 호출 중 정상 워커가 stale로 오인돼 다른 워커에게 재선점되지 않게 한다. */
    @Transactional
    public boolean heartbeat(Collection<Long> ids, String workerId) {
        return !ids.isEmpty() && analysisRepository.heartbeat(ids, workerId) > 0;
    }
}
