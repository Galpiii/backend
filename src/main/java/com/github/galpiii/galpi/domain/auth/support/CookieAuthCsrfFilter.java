package com.github.galpiii.galpi.domain.auth.support;

import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 쿠키만으로 인증하는 엔드포인트에 커스텀 헤더를 요구한다.
 * <p>
 * {@code /auth/refresh}와 {@code /auth/logout}은 Authorization 헤더 없이 Refresh 쿠키 하나로
 * 동작하므로, 교차 사이트에서 그대로 호출될 수 있는 CSRF 표적이다. SameSite=Lax가 지금은 막아 주지만
 * 프론트와 백엔드가 다른 사이트라 쿠키를 None으로 바꾸는 순간 그 방어가 사라진다.
 * <p>
 * 커스텀 헤더는 교차 사이트 폼으로는 붙일 수 없고, fetch로 붙이면 프리플라이트가 발생해 CORS 허용
 * 목록에 걸린다. 그래서 SameSite 설정과 무관하게 성립한다. 프론트는 이 두 엔드포인트를 호출할 때
 * {@code X-Galpi-Request: 1}을 보내야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CookieAuthCsrfFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Galpi-Request";

    private static final Set<String> PROTECTED_PATHS = Set.of("/auth/refresh", "/auth/logout");

    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PROTECTED_PATHS.contains(pathWithinApplication(request));
    }

    /**
     * getRequestURI()는 컨텍스트 패스를 포함한다. 그대로 비교하면 나중에 context-path가 붙는 순간
     * 아무 경로에도 걸리지 않아 이 방어가 조용히 꺼진다.
     */
    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath.isEmpty() ? uri : uri.substring(contextPath.length());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getHeader(HEADER) == null) {
            log.info("[Auth] {} 헤더 없이 쿠키 인증 엔드포인트 호출 path={}", HEADER, request.getRequestURI());
            reject(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        ErrorCode errorCode = ErrorCode.CSRF_HEADER_REQUIRED;
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.error(errorCode.getCode(), errorCode.getMessage()));
    }
}
