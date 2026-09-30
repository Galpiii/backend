package com.github.galpiii.galpi.domain.featurematch.worker;

import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
import com.github.galpiii.galpi.domain.featurematch.service.FeatureMatchWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * DB cascade 등으로 마지막 대상이 사라진 실행만 작은 트랜잭션으로 마무리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureMatchReconciler {

    private final FeatureMatchQueryRepository queryRepository;
    private final FeatureMatchWriter writer;

    public void reconcile() {
        for (Long runId : queryRepository.finalizableRunIds(Limit.of(50))) {
            try {
                writer.reconcile(runId);
            } catch (RuntimeException exception) {
                log.warn("[기능대조] 실행 복구 실패 runId={} type={}",
                        runId, exception.getClass().getSimpleName());
            }
        }
    }
}
