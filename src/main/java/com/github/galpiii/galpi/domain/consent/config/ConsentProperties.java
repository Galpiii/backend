package com.github.galpiii.galpi.domain.consent.config;

import com.github.galpiii.galpi.domain.consent.AiDataNotice;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 현재 적용 중인 동의 정책 버전.
 *
 * <p>기본값은 코드가 들고 있는 {@link AiDataNotice#CURRENT_VERSION}이다. 설정으로 덮을 수 있게
 * 남겨 둔 것은 재동의를 코드 배포 없이 걸어야 할 때가 있기 때문인데, 그때도 <b>문구가 등록된
 * 버전</b>만 허용한다. 없는 버전을 넣으면 사용자가 읽은 적 없는 버전에 동의를 받게 되므로
 * 기동 시점에 실패시킨다.
 *
 * @param aiDataVersion 외부 AI 전송 고지의 버전. {@code ai_data_consents.consent_version}에
 *                      그대로 저장되므로 컬럼 폭(20자)을 넘기지 않는다
 */
@Validated
@ConfigurationProperties(prefix = "galpi.consent")
public record ConsentProperties(
        @DefaultValue(AiDataNotice.CURRENT_VERSION) @NotBlank @Size(max = 20) String aiDataVersion
) {

    public ConsentProperties {
        if (!AiDataNotice.hasVersion(aiDataVersion)) {
            throw new IllegalArgumentException(
                    "galpi.consent.ai-data-version에 문구가 등록되지 않은 버전이 지정됐습니다: "
                            + aiDataVersion);
        }
    }
}
