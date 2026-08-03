package com.github.galpiii.galpi.domain.auth.support;

import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedirectUriValidator {

    /**
     * 브라우저는 URL의 백슬래시를 슬래시로 정규화한다(WHATWG URL). 그래서 {@code /\evil.com}은
     * {@code //evil.com}, 즉 프로토콜 상대 URL이 되어 외부로 나간다. 제어문자도 같은 이유로 막는다.
     */
    private static final Pattern PATH_TRAVERSAL_TO_HOST = Pattern.compile("[\\\\\\p{Cntrl}]");

    private final GithubAppProperties properties;

    public String validate(String returnTo) {
        if (returnTo == null || returnTo.isBlank()) {
            return "";
        }
        if (PATH_TRAVERSAL_TO_HOST.matcher(returnTo).find()) {
            log.warn("[Auth] 백슬래시·제어문자가 섞인 리다이렉트 대상 요청");
            throw new BadRequestException(ErrorCode.GITHUB_REDIRECT_NOT_ALLOWED);
        }
        if (returnTo.startsWith("/") && !returnTo.startsWith("//")) {
            return returnTo;
        }
        if (isAllowedOrigin(returnTo)) {
            return returnTo;
        }
        log.warn("[Auth] 허용되지 않은 리다이렉트 대상 요청");
        throw new BadRequestException(ErrorCode.GITHUB_REDIRECT_NOT_ALLOWED);
    }

    public String buildFrontendCallback(String loginCode, String returnTo) {
        return buildFrontendUrl("code", loginCode, returnTo);
    }

    public String buildFrontendError(String errorCode, String returnTo) {
        return buildFrontendUrl("error", errorCode, returnTo);
    }

    private String buildFrontendUrl(String paramName, String paramValue, String returnTo) {
        StringBuilder url = new StringBuilder(properties.defaultRedirectUri());
        url.append(properties.defaultRedirectUri().contains("?") ? '&' : '?')
                .append(paramName)
                .append('=')
                .append(urlEncode(paramValue));
        if (returnTo != null && !returnTo.isBlank()) {
            url.append("&returnTo=").append(urlEncode(returnTo));
        }
        return url.toString();
    }

    private boolean isAllowedOrigin(String candidate) {
        try {
            URI uri = new URI(candidate);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return false;
            }
            String origin = uri.getScheme() + "://" + uri.getAuthority();
            return properties.allowedRedirectOrigins().stream().anyMatch(origin::equalsIgnoreCase);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
