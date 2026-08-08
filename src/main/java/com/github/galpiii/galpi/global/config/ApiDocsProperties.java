package com.github.galpiii.galpi.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * API 문서 노출 여부.
 *
 * <p>기본값은 false다. 인증 엔드포인트의 스펙 전체를 공개하는 일이 환경변수를 빠뜨렸다는
 * 이유만으로 일어나서는 안 되므로, 켜는 쪽을 명시적인 선택으로 둔다.
 */
@ConfigurationProperties(prefix = "galpi.api-docs")
public record ApiDocsProperties(@DefaultValue("false") boolean enabled) {
}
