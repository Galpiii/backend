package com.github.galpiii.galpi.domain.auth.support;

import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 오픈 리다이렉트 방지.
 *
 * <p>로그인 완료 후 사용자를 어디로 보낼지는 클라이언트가 요청할 수 있지만, 대상은 반드시
 * 화이트리스트에 있는 origin이거나 사이트 내부 상대경로여야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedirectUriValidator {

    private final GithubAppProperties properties;

    /**
     * 로그인 시작 시 받은 복귀 대상을 검증한다.
     *
     * @param returnTo 절대 URL(화이트리스트 origin) 또는 {@code /}로 시작하는 상대경로. null 허용
     * @return 검증된 값. 비어 있으면 빈 문자열
     * @throws BadRequestException 허용되지 않은 대상
     */
    public String validate(String returnTo) {
        if (returnTo == null || returnTo.isBlank()) {
            return "";
        }
        // "//evil.com"은 프로토콜 상대 URL이라 외부로 나간다. 상대경로로 취급하면 안 된다.
        if (returnTo.startsWith("/") && !returnTo.startsWith("//")) {
            return returnTo;
        }
        if (isAllowedOrigin(returnTo)) {
            return returnTo;
        }
        log.warn("[Auth] 허용되지 않은 리다이렉트 대상 요청");
        throw new BadRequestException(ErrorCode.GITHUB_REDIRECT_NOT_ALLOWED);
    }

    /**
     * 최종 이동할 프론트 주소를 만든다. 기본 콜백 주소에 일회용 코드를 붙인다.
     */
    public String buildFrontendCallback(String loginCode, String returnTo) {
        return buildFrontendUrl("code", loginCode, returnTo);
    }

    /**
     * OAuth 실패를 프론트 오류 화면으로 넘긴다. 상세 사유는 담지 않는다.
     *
     * @param returnTo 로그인을 시작한 위치. 알 수 있으면 실패 후에도 그대로 돌려보낸다
     */
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
