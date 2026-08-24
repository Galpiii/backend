package com.github.galpiii.galpi.domain.consent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * @param consentVersion 사용자가 실제로 읽은 고지의 버전. 서버의 현재 버전과 다르면 거부한다 —
 *                       화면이 옛 문구를 띄운 채 최신 버전에 동의한 것으로 기록되면 동의 기록이
 *                       근거 역할을 못 한다
 */
public record AiDataConsentRequest(
        @Schema(description = "동의 화면에 표시된 고지 버전", example = "2026-08-23")
        @NotBlank(message = "동의 버전은 필수입니다.") String consentVersion
) {
}
