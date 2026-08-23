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

    /**
     * 오류가 아닌 결과를 프론트로 넘긴다. 설치 콜백처럼 "성공/실패"가 아니라 "확인됨/확인 안 됨"
     * 같은 상태를 전달해야 하는 경우에 쓴다.
     */
    public String buildFrontendResult(String paramName, String value, String returnTo) {
        return buildFrontendUrl(paramName, value, returnTo);
    }

    /**
     * 이미 만들어진 프론트 URL에 파라미터를 하나 더 붙인다.
     *
     * <p>{@code buildFrontendUrl}이 항상 {@code ?}를 포함한 URL을 만들기 때문에 구분자는 늘
     * {@code &}다. 이 메서드는 그 결과에만 쓴다.
     */
    public String withParam(String url, String name, String value) {
        return url + '&' + name + '=' + urlEncode(value);
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
