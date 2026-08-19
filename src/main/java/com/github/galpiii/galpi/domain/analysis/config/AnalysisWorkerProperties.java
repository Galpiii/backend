package com.github.galpiii.galpi.domain.analysis.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * DB 폴링 워커 설정.
 *
 * @param lease       선점이 유효한 기간. 이보다 오래된 선점은 다른 워커가 가져갈 수 있다
 * @param maxAttempts 이 횟수를 넘게 시도된 작업은 더 돌리지 않고 실패로 끝낸다
 * @param rateLimitThreshold 남은 호출 수가 이 값 이하면 다음 저장소를 시작하지 않는다.
 *                           저장소 하나에 최대 900번을 쓰므로, 403을 맞고 나서야 멈추면
 *                           절반쯤 수집한 저장소를 버리게 된다. 한도 자체는 응답 헤더에서
 *                           읽고 상수로 박지 않는다 — installation 토큰의 한도는 저장소·사용자
 *                           수에 따라 5,000에서 12,500까지 달라진다
 */
@Validated
@ConfigurationProperties(prefix = "galpi.analysis.worker")
public record AnalysisWorkerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5s") @NotNull Duration pollInterval,
        @DefaultValue("30m") @NotNull Duration lease,
        @DefaultValue("3") @Min(1) int maxAttempts,
        @DefaultValue("100") @Min(0) int rateLimitThreshold
) {
}
