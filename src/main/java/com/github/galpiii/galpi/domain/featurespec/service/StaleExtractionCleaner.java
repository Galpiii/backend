package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.ai.config.OpenAiProperties;
import com.github.galpiii.galpi.domain.featurespec.config.FeatureExtractionProperties;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecTempFileStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * 죽은 프로세스가 남긴 유령 상태와 임시 파일을 주기적으로 정리한다.
 *
 * <p>재배포로 프로세스가 죽으면 큐에 있던 작업과 실행 중이던 작업이 함께 사라지는데, 문서는
 * PENDING이나 PROCESSING으로 남고 분석에 쓰려던 PDF도 지울 주체를 잃는다.
 *
 * <p>기동 시 한 번만 돌면 두 가지가 어긋난다. 배포 중 새 프로세스가 구 프로세스보다 먼저 뜨는
 * 경우 아직 살아 있는 분석까지 죽이고, 반대로 재기동이 빨라 유령이 아직 어리면 영영 정리되지
 * 않는다. 그래서 상태가 아니라 나이로 판단하고, 나이가 차기를 기다린다.
 */
@Slf4j
@Profile("!test")
@Component
@RequiredArgsConstructor
class StaleExtractionCleaner {

    /**
     * 계산한 최악의 수명에 곱하는 여유. 큐 대기는 산술적인 하한이라 스케줄 간격이나 재기동이
     * 끼면 조금씩 밀린다. 살아 있는 작업을 죽이는 쪽이 유령을 늦게 치우는 쪽보다 나쁘다.
     */
    private static final int SAFETY_FACTOR = 2;

    private final FeatureExtractionWriter featureExtractionWriter;
    private final FeatureSpecTempFileStore tempFileStore;
    private final FeatureExtractionProperties properties;
    private final OpenAiProperties openAiProperties;

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public void cleanStale() {
        try {
            int cleaned = featureExtractionWriter.failStale(OffsetDateTime.now().minus(staleAfter()));

            if (cleaned > 0) {
                log.warn("[기능명세서 분석] 죽은 프로세스가 남긴 분석 {}건을 실패로 정리했습니다.", cleaned);
            }
        } catch (RuntimeException e) {
            log.error("[기능명세서 분석] 유령 분석 정리 배치가 실패했습니다.", e);
        }
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void cleanStaleTempFiles() {
        try {
            int deleted = tempFileStore.deleteOlderThan(Instant.now().minus(staleAfter()));

            if (deleted > 0) {
                log.warn("[기능명세서 분석] 주인이 사라진 임시 파일 {}건을 지웠습니다.", deleted);
            }
        } catch (RuntimeException e) {
            log.error("[기능명세서 분석] 임시 파일 정리 배치가 실패했습니다.", e);
        }
    }

    /**
     * 접수된 분석이 정상적으로 살아 있을 수 있는 최대 시간.
     *
     * <p>고정값으로 두면 큐 용량이나 분석 예산을 올렸을 때 아직 차례를 기다리는 작업을 죽은
     * 것으로 보고 문서를 실패시키거나, 더 나쁘게는 그 작업이 쓸 임시 PDF를 먼저 지운다. 그래서
     * 설정에서 직접 계산한다.
     *
     * <p>큐가 가득 찼을 때 마지막 작업은 앞선 작업들이 워커 수만큼 나뉘어 끝나기를 기다린 뒤
     * 자기 분석을 시작한다. 한 건의 최대 수명은 예산에 진행 중인 호출의 타임아웃을 더한 값이다.
     */
    private Duration staleAfter() {
        Duration perTask = openAiProperties.analysisBudget().plus(openAiProperties.timeout());
        int queuedBatches = (properties.queueCapacity() / properties.maxPoolSize()) + 1;

        return perTask.multipliedBy((long) queuedBatches * SAFETY_FACTOR);
    }
}
