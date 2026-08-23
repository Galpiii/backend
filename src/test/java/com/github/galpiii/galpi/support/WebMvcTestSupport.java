package com.github.galpiii.galpi.support;

import com.github.galpiii.galpi.domain.analysis.service.AnalysisRunService;
import com.github.galpiii.galpi.domain.auth.config.JwtProperties;
import com.github.galpiii.galpi.domain.auth.jwt.JwtAccessDeniedHandler;
import com.github.galpiii.galpi.domain.auth.jwt.JwtAuthenticationEntryPoint;
import com.github.galpiii.galpi.domain.auth.jwt.JwtAuthenticationFilter;
import com.github.galpiii.galpi.domain.auth.jwt.JwtTokenProvider;
import com.github.galpiii.galpi.domain.auth.service.AuthService;
import com.github.galpiii.galpi.domain.auth.support.RefreshCookieFactory;
import com.github.galpiii.galpi.domain.featurespec.service.FeatureSpecService;
import com.github.galpiii.galpi.domain.github.service.GithubConnectionService;
import com.github.galpiii.galpi.domain.github.service.GithubInstallationService;
import com.github.galpiii.galpi.domain.github.service.GithubOAuthService;
import com.github.galpiii.galpi.domain.github.service.GithubSetupService;
import com.github.galpiii.galpi.domain.project.service.ProjectRepositoryService;
import com.github.galpiii.galpi.domain.project.service.ProjectService;
import com.github.galpiii.galpi.domain.github.support.OAuthStateCookieFactory;
import com.github.galpiii.galpi.global.config.ApiDocsProperties;
import com.github.galpiii.galpi.global.config.CorsProperties;
import com.github.galpiii.galpi.global.config.SecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
@ActiveProfiles("test")
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class,
        JwtTokenProvider.class,
        RefreshCookieFactory.class,
        OAuthStateCookieFactory.class
})
@EnableConfigurationProperties({JwtProperties.class, CorsProperties.class, ApiDocsProperties.class})
public abstract class WebMvcTestSupport {

    @Autowired
    protected MockMvc mockMvc;

    @MockitoBean
    protected AuthService authService;
    @MockitoBean
    protected GithubOAuthService githubOAuthService;
    @MockitoBean
    protected GithubConnectionService githubConnectionService;
    @MockitoBean
    protected GithubSetupService githubSetupService;
    @MockitoBean
    protected GithubInstallationService githubInstallationService;
    @MockitoBean
    protected ProjectRepositoryService projectRepositoryService;
    @MockitoBean
    protected ProjectService projectService;
    // @WebMvcTest는 슬라이스를 좁히지 않아 컨트롤러를 전부 올린다. 컨트롤러가 늘면 그 의존도
    // 여기 추가해야 하며, 빠뜨리면 이 클래스를 쓰는 모든 테스트가 함께 죽는다.
    @MockitoBean
    protected FeatureSpecService featureSpecService;
    @MockitoBean
    protected AnalysisRunService analysisRunService;
}
