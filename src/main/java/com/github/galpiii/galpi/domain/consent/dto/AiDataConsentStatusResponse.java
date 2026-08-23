package com.github.galpiii.galpi.domain.consent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 동의 화면이 필요한지 판단하는 데 쓰는 응답.
 *
 * <p>{@code agreed}가 false면 분석 실행이 막혀 있다. {@code agreedVersion}이 있는데도 false면
 * 처음 동의가 아니라 <b>재동의</b>다 — 정책이 바뀌었다는 뜻이므로 화면 문구가 달라야 한다.
 */
@Schema(description = "외부 AI 전송 동의 상태")
public record AiDataConsentStatusResponse(
        @Schema(description = "현재 적용 중인 고지 버전. 동의 요청에 이 값을 그대로 보낸다")
        String currentVersion,
        @Schema(description = "현재 버전에 동의했는지. false면 분석을 실행할 수 없다")
        boolean agreed,
        @Schema(description = "사용자가 마지막으로 동의한 버전. 동의한 적이 없으면 null")
        String agreedVersion,
        @Schema(description = "마지막 동의 시각") OffsetDateTime agreedAt,
        @Schema(description = "현재 버전의 고지 문구") String notice
) {
}
