package com.github.galpiii.galpi.domain.featurespec.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 이전 실행이 남긴 유령 상태를 기동 시점에 정리한다.
 *
 * <p>재배포로 프로세스가 죽으면 큐에 있던 작업과 실행 중이던 작업이 함께 사라지는데, 문서는
 * PENDING이나 PROCESSING으로 남는다. 그대로 두면 프론트가 끝나지 않는 상태를 계속 polling한다.
 *
 * <p>인스턴스가 여러 대가 되면 다른 서버가 처리 중인 문서까지 실패로 바꾸므로 쓸 수 없다.
 * ponytail: 단일 인스턴스 전제. 여러 대로 늘면 updatedAt이 일정 시간 지난 PROCESSING만 정리하는
 * 스케줄러로 교체한다.
 */
@Slf4j
@Profile("!test")
@Component
@RequiredArgsConstructor
class StaleExtractionCleaner implements ApplicationRunner {

    private final FeatureExtractionWriter featureExtractionWriter;

    @Override
    public void run(ApplicationArguments args) {
        int cleaned = featureExtractionWriter.failAllInProgress();

        if (cleaned > 0) {
            log.warn("[기능명세서 분석] 이전 실행에서 남은 분석 {}건을 실패로 정리했습니다.", cleaned);
        }
    }
}
