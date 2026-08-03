package com.github.galpiii.galpi.support;

import com.github.galpiii.galpi.domain.auth.config.JwtProperties;
import com.github.galpiii.galpi.domain.auth.jwt.JwtAccessDeniedHandler;
import com.github.galpiii.galpi.domain.auth.jwt.JwtAuthenticationEntryPoint;
import com.github.galpiii.galpi.domain.auth.jwt.JwtAuthenticationFilter;
import com.github.galpiii.galpi.domain.auth.jwt.JwtTokenProvider;
import com.github.galpiii.galpi.domain.auth.service.AuthService;
import com.github.galpiii.galpi.domain.auth.support.RefreshCookieFactory;
import com.github.galpiii.galpi.domain.github.service.GithubConnectionService;
import com.github.galpiii.galpi.domain.github.service.GithubOAuthService;
import com.github.galpiii.galpi.global.config.CorsProperties;
import com.github.galpiii.galpi.global.config.SecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 계층 슬라이스 테스트의 공통 배선.
 * <p>
 * 시큐리티 필터 체인을 실제로 태운다. 어떤 엔드포인트가 인증 없이 열려 있는지, 쿠키에 어떤 속성이
 * 붙는지는 설정 한 줄로 뒤집히는데 서비스 단위 테스트로는 잡히지 않으므로 여기서 검증한다.
 * 서비스는 전부 목이며, 컨트롤러·필터·시큐리티 설정만 진짜다.
 */
@WebMvcTest
@ActiveProfiles("test")
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class,
        JwtTokenProvider.class,
        RefreshCookieFactory.class
})
// 슬라이스 테스트에는 @ConfigurationPropertiesScan이 적용되지 않아 직접 등록해야 한다.
@EnableConfigurationProperties({JwtProperties.class, CorsProperties.class})
public abstract class WebMvcTestSupport {

    @Autowired
    protected MockMvc mockMvc;

    @MockitoBean
    protected AuthService authService;
    @MockitoBean
    protected GithubOAuthService githubOAuthService;
    @MockitoBean
    protected GithubConnectionService githubConnectionService;
}
