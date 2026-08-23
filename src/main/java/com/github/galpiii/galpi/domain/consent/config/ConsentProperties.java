package com.github.galpiii.galpi.domain.consent.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 현재 적용 중인 동의 정책 버전.
 *
 * <p>외부 LLM 제공자나 보관 정책이 바뀌면 이 값을 올린다. 그 순간부터 이전 버전에만 동의한
 * 사용자는 분석을 실행하기 전에 재동의를 요구받는다 — 코드 배포 없이 재동의를 걸 수 있어야
 * 하므로 상수가 아니라 설정값이다.
 *
 * @param aiDataVersion 외부 AI 전송 고지의 버전. {@code ai_data_consents.consent_version}에
 *                      그대로 저장되므로 컬럼 폭(20자)을 넘기지 않는다
 */
@Validated
@ConfigurationProperties(prefix = "galpi.consent")
public record ConsentProperties(
        @DefaultValue("2026-08-23") @NotBlank @Size(max = 20) String aiDataVersion
) {
}
