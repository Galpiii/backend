package com.github.galpiii.galpi.domain.github.client;

import com.github.galpiii.galpi.domain.github.client.dto.GithubAccessTokenResponse;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("GithubOAuthClient")
class GithubOAuthClientTest {

    private static final String TOKEN_ENDPOINT =
            GithubTestClients.OAUTH_BASE_URL + "/login/oauth/access_token";

    private MockRestServiceServer server;
    private GithubOAuthClient client;

    @BeforeEach
    void setUp() {
        GithubAppProperties properties = GithubTestClients.properties();
        RestClient.Builder builder = GithubTestClients.config()
                .oAuthClientBuilder(properties, new RateLimitRecorder());
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GithubOAuthClient(builder.build(), properties);
    }

    @Nested
    @DisplayName("authorize URL")
    class AuthorizeUrl {

        @Test
        @DisplayName("client_id·redirect_uri·state를 담는다")
        void includesRequiredParams() {
            String url = client.buildAuthorizeUrl("st4te-value");

            assertThat(url)
                    .startsWith(GithubTestClients.OAUTH_BASE_URL + "/login/oauth/authorize")
                    .contains("client_id=Iv1.testclient")
                    .contains("state=st4te-value")
                    .contains("redirect_uri=https://api.galpi.dev/auth/github/callback");
        }

        @Test
        @DisplayName("GitHub App은 scope를 쓰지 않으므로 붙이지 않는다")
        void omitsScope() {
            assertThat(client.buildAuthorizeUrl("st4te")).doesNotContain("scope");
        }
    }

    @Nested
    @DisplayName("code 교환")
    class ExchangeCode {

        @Test
        @DisplayName("자격증명을 form으로 보내고 토큰 응답을 파싱한다")
        void exchangesCode() {
            server.expect(requestTo(TOKEN_ENDPOINT))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                    .andExpect(content().formData(formData()))
                    .andRespond(withSuccess("""
                            {"access_token":"ghu_token","token_type":"bearer","expires_in":28800,
                             "refresh_token":"ghr_token","refresh_token_expires_in":15897600}""",
                            MediaType.APPLICATION_JSON));

            GithubAccessTokenResponse response = client.exchangeCodeForToken("the-code");

            assertThat(response.accessToken()).isEqualTo("ghu_token");
            assertThat(response.hasExpiry()).isTrue();
            assertThat(response.expiresIn()).hasSeconds(28800);
            server.verify();
        }

        @Test
        @DisplayName("expires_in이 없으면 만료 비활성으로 읽힌다")
        void detectsMissingExpiry() {
            server.expect(requestTo(TOKEN_ENDPOINT))
                    .andRespond(withSuccess("""
                            {"access_token":"ghu_token","token_type":"bearer"}""",
                            MediaType.APPLICATION_JSON));

            GithubAccessTokenResponse response = client.exchangeCodeForToken("the-code");

            assertThat(response.hasExpiry()).isFalse();
            assertThat(response.expiresIn()).isNull();
            assertThat(response.refreshToken()).isNull();
        }

        @Test
        @DisplayName("200이어도 error 본문이면 실패로 처리한다")
        void treatsErrorBodyAsFailure() {
            server.expect(requestTo(TOKEN_ENDPOINT))
                    .andRespond(withSuccess("""
                            {"error":"bad_verification_code",
                             "error_description":"The code passed is incorrect or expired."}""",
                            MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.exchangeCodeForToken("reused-code"))
                    .isInstanceOf(UnauthorizedException.class)
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.GITHUB_OAUTH_FAILED);
        }

        @Test
        @DisplayName("4xx 응답도 로그인 실패로 처리한다")
        void handlesHttpError() {
            server.expect(requestTo(TOKEN_ENDPOINT))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST));

            assertThatThrownBy(() -> client.exchangeCodeForToken("the-code"))
                    .isInstanceOf(UnauthorizedException.class);
        }

        @Test
        @DisplayName("토큰 응답의 toString에 access_token이 남지 않는다")
        void toStringHidesToken() {
            GithubAccessTokenResponse response = new GithubAccessTokenResponse(
                    "ghu_supersecret", "bearer", 28800L, "ghr_secret", 100L, null, null);

            assertThat(response.toString())
                    .doesNotContain("ghu_supersecret")
                    .doesNotContain("ghr_secret");
        }

        private org.springframework.util.MultiValueMap<String, String> formData() {
            org.springframework.util.LinkedMultiValueMap<String, String> form =
                    new org.springframework.util.LinkedMultiValueMap<>();
            form.add("client_id", "Iv1.testclient");
            form.add("client_secret", "test-client-secret");
            form.add("code", "the-code");
            form.add("redirect_uri", "https://api.galpi.dev/auth/github/callback");
            return form;
        }
    }
}
