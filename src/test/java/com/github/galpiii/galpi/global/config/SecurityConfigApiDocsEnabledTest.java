package com.github.galpiii.galpi.global.config;

import com.github.galpiii.galpi.support.WebMvcTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 문서를 켠 환경에서는 같은 경로가 시큐리티를 통과해야 한다.
 *
 * <p>@WebMvcTest 슬라이스에는 springdoc 컨트롤러가 없으므로 통과의 증거는 404다.
 * 401이 아니라는 점이 핵심이다 — 401이면 시큐리티가 막은 것이고, 404면 통과한 뒤
 * 처리할 핸들러가 없다는 뜻이다.
 */
@TestPropertySource(properties = "galpi.api-docs.enabled=true")
@DisplayName("SecurityConfig — API 문서를 켠 환경")
class SecurityConfigApiDocsEnabledTest extends WebMvcTestSupport {

    @ParameterizedTest(name = "GET {0} 은 시큐리티를 통과한다")
    @ValueSource(strings = {"/swagger-ui/index.html", "/v3/api-docs"})
    @DisplayName("켜면 인증 없이 열린다")
    void openWhenEnabled(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isNotFound());
    }
}
