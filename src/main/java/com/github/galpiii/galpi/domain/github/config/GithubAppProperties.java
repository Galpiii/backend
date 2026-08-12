package com.github.galpiii.galpi.domain.github.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "galpi.github")
public record GithubAppProperties(
        @NotBlank String appId,
        @NotBlank String appSlug,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String privateKey,
        @NotBlank String baseUrl,
        @DefaultValue("2022-11-28") String apiVersion,
        @DefaultValue("https://api.github.com") String apiBaseUrl,
        @DefaultValue("https://github.com") String oauthBaseUrl,
        @DefaultValue("Galpi") String userAgent,
        @DefaultValue List<String> allowedRedirectOrigins,
        @NotBlank String defaultRedirectUri,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout,
        @DefaultValue("2") int maxRetries,
        @DefaultValue("10") int maxPages
) {

    public String normalizedPrivateKey() {
        return privateKey.replace("\\n", "\n").trim();
    }

    public String oauthCallbackUrl() {
        return trimTrailingSlash(baseUrl) + "/auth/github/callback";
    }

    /**
     * App 설치 페이지. {@code /installations/new}가 맞는 엔드포인트다.
     *
     * <p>한동안 이 경로가 {@code state}를 유실해 {@code select_target}을 대신 쓰라는 이야기가
     * 돌았지만, GitHub이 2023-07 리다이렉트 시 쿼리를 넘기도록 고쳤고 {@code select_target}은
     * OAuth 흐름 내부용이라 직접 링크하지 말라고 명시했다.
     */
    public String installUrl(String state) {
        return trimTrailingSlash(oauthBaseUrl) + "/apps/" + appSlug + "/installations/new"
                + "?state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
    }

    /**
     * 설치의 저장소 선택을 바꾸는 설정 페이지. 개인 계정과 조직이 경로가 다르다.
     * "GitHub에서 저장소 추가" 링크가 여기로 간다.
     */
    public String installationSettingsUrl(Long installationId, String accountLogin,
                                          boolean organization) {
        String base = trimTrailingSlash(oauthBaseUrl);
        if (organization) {
            return base + "/organizations/" + accountLogin
                    + "/settings/installations/" + installationId;
        }
        return base + "/settings/installations/" + installationId;
    }

    public String authorizeUrl() {
        return trimTrailingSlash(oauthBaseUrl) + "/login/oauth/authorize";
    }

    public String accessTokenUrl() {
        return trimTrailingSlash(oauthBaseUrl) + "/login/oauth/access_token";
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
