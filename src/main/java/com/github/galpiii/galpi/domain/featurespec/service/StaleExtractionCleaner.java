package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.config.FeatureExtractionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 죽은 프로세스가 남긴 유령 상태를 주기적으로 정리한다.
 *
 * <p>재배포로 프로세스가 죽으면 큐에 있던 작업과 실행 중이던 작업이 함께 사라지는데, 문서는
 * PENDING이나 PROCESSING으로 남는다. 그대로 두면 프론트가 끝나지 않는 상태를 계속 polling한다.
 *
 * <p>기동 시 한 번만 돌면 두 가지가 어긋난다. 배포 중 새 프로세스가 구 프로세스보다 먼저 뜨는
 * 경우 아직 살아 있는 분석까지 죽이고, 반대로 재기동이 빨라 유령 행이 아직 어리면 그 행은
 * 영영 정리되지 않는다. 그래서 상태가 아니라 나이로 판단하고, 나이가 차기를 기다린다.
 */
@Slf4j
@Profile("!test")
@Component
@RequiredArgsConstructor
class StaleExtractionCleaner {

    private final FeatureExtractionWriter featureExtractionWriter;
    private final FeatureExtractionProperties properties;

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public void cleanStale() {
        try {
            int cleaned = featureExtractionWriter.failStale(
                    OffsetDateTime.now().minus(properties.staleAfter()));

            if (cleaned > 0) {
                log.warn("[기능명세서 분석] 죽은 프로세스가 남긴 분석 {}건을 실패로 정리했습니다.", cleaned);
            }
        } catch (RuntimeException e) {
            log.error("[기능명세서 분석] 유령 분석 정리 배치가 실패했습니다.", e);
        }
    }
}
