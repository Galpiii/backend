package com.github.galpiii.galpi.domain.github.client;

import com.github.galpiii.galpi.domain.github.client.dto.GithubUserResponse;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.domain.github.exception.GithubReauthRequiredException;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("GithubApiClient")
class GithubApiClientTest {

    private static final String TOKEN = "ghu_abcdefghijklmnopqrstuvwxyz012345";

    private MockRestServiceServer server;
    private GithubApiClient client;

    private void initClient(GithubAppProperties properties) {
        RestClient.Builder builder = GithubTestClients.config()
                .apiClientBuilder(properties, new RateLimitRecorder());
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GithubApiClient(builder.build(), properties);
    }

    @BeforeEach
    void setUp() {
        initClient(GithubTestClients.properties());
    }

    @Nested
    @DisplayName("공통 규약 (§6)")
    class CommonContract {

        @Test
        @DisplayName("모든 요청에 Accept·API 버전·User-Agent·Authorization을 붙인다")
        void sendsRequiredHeaders() {
            server.expect(requestTo(GithubTestClients.API_BASE_URL + "/user"))
                    .andExpect(method(org.springframework.http.HttpMethod.GET))
                    .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
                    .andExpect(header("X-GitHub-Api-Version", GithubTestClients.API_VERSION))
                    .andExpect(header(HttpHeaders.USER_AGENT, "Galpi"))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                    .andRespond(withSuccess("""
                            {"id":1,"login":"octocat","avatar_url":"https://avatars/1"}""",
                            MediaType.APPLICATION_JSON));

            client.getAuthenticatedUser(TOKEN);

            server.verify();
        }

        @Test
        @DisplayName("/user 응답을 파싱한다")
        void parsesAuthenticatedUser() {
            server.expect(requestTo(GithubTestClients.API_BASE_URL + "/user"))
                    .andRespond(withSuccess("""
                            {"id":583231,"login":"octocat","avatar_url":"https://avatars/583231",
                             "email":null,"name":"The Octocat"}""",
                            MediaType.APPLICATION_JSON));

            GithubUserResponse user = client.getAuthenticatedUser(TOKEN);

            assertThat(user.id()).isEqualTo(583231L);
            assertThat(user.login()).isEqualTo("octocat");
            assertThat(user.email()).isNull();
        }
    }

    @Nested
    @DisplayName("오류 구분 (§8)")
    class ErrorMapping {

        @Test
        @DisplayName("401은 GitHub 재인증 요구로 바꾼다")
        void mapsUnauthorizedToReauth() {
            server.expect(requestTo(GithubTestClients.API_BASE_URL + "/user"))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                            .body("{\"message\":\"Bad credentials\"}")
                            .contentType(MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.getAuthenticatedUser(TOKEN))
                    .isInstanceOf(GithubReauthRequiredException.class)
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.GITHUB_REAUTH_REQUIRED);
        }

        @Test
        @DisplayName("403 + remaining 0은 rate limit으로 판정한다")
        void mapsExhaustedRateLimit() {
            server.expect(requestTo(GithubTestClients.API_BASE_URL + "/user"))
                    .andRespond(withStatus(HttpStatus.FORBIDDEN)
                            .header(RateLimitSnapshot.HEADER_LIMIT, "5000")
                            .header(RateLimitSnapshot.HEADER_REMAINING, "0")
                            .header(RateLimitSnapshot.HEADER_RESET, "1754006400")
                            .body("{\"message\":\"API rate limit exceeded\"}")
                            .contentType(MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.getAuthenticatedUser(TOKEN))
                    .isInstanceOf(GithubApiException.class)
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.GITHUB_RATE_LIMITED);
        }

        @Test
        @DisplayName("403 + Retry-After는 secondary rate limit으로 판정한다")
        void mapsSecondaryRateLimit() {
            server.expect(requestTo(GithubTestClients.API_BASE_URL + "/user"))
                    .andRespond(withStatus(HttpStatus.FORBIDDEN)
                            .header(HttpHeaders.RETRY_AFTER, "60")
                            .body("{\"message\":\"You have exceeded a secondary rate limit\"}")
                            .contentType(MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.getAuthenticatedUser(TOKEN))
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.GITHUB_RATE_LIMITED);
        }

        @Test
        @DisplayName("remaining이 남은 403은 rate limit이 아니라 권한 오류다")
        void distinguishesPermissionErrorFromRateLimit() {
            server.expect(requestTo(GithubTestClients.API_BASE_URL + "/user"))
                    .andRespond(withStatus(HttpStatus.FORBIDDEN)
                            .header(RateLimitSnapshot.HEADER_LIMIT, "5000")
                            .header(RateLimitSnapshot.HEADER_REMAINING, "4998")
                            .body("{\"message\":\"Resource not accessible by integration\"}")
                            .contentType(MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.getAuthenticatedUser(TOKEN))
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.GITHUB_API_ERROR);
        }

        @Test
        @DisplayName("404는 일반 API 오류로 넘기고 호출부가 저장소 단위로 처리한다")
        void mapsNotFound() {
            server.expect(requestTo(GithubTestClients.API_BASE_URL + "/user"))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND)
                            .body("{\"message\":\"Not Found\"}")
                            .contentType(MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.getAuthenticatedUser(TOKEN))
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.GITHUB_API_ERROR);
        }
    }

    @Nested
    @DisplayName("재시도 정책")
    class RetryPolicy {

        @Test
        @DisplayName("5xx는 재시도한다")
        void retriesServerErrors() {
            server.expect(requestTo(GithubTestClients.API_BASE_URL + "/user"))
                    .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
            server.expect(requestTo(GithubTestClients.API_BASE_URL + "/user"))
                    .andRespond(withSuccess("{\"id\":1,\"login\":\"octocat\"}", MediaType.APPLICATION_JSON));

            GithubUserResponse user = client.getAuthenticatedUser(TOKEN);

            assertThat(user.id()).isEqualTo(1L);
            server.verify();
        }

        @Test
        @DisplayName("재시도 뒤 최종 응답의 rate limit도 기록된다")
        void recordsRateLimitOfFinalResponse() {
            RateLimitRecorder recorder = new RateLimitRecorder();
            GithubAppProperties properties = GithubTestClients.properties();
            RestClient.Builder builder = GithubTestClients.config().apiClientBuilder(properties, recorder);
            MockRestServiceServer retryServer = MockRestServiceServer.bindTo(builder).build();
            GithubApiClient retryClient = new GithubApiClient(builder.build(), properties);

            retryServer.expect(requestTo(GithubTestClients.API_BASE_URL + "/user"))
                    .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
            retryServer.expect(requestTo(GithubTestClients.API_BASE_URL + "/user"))
                    .andRespond(withSuccess("{\"id\":1,\"login\":\"octocat\"}", MediaType.APPLICATION_JSON)
                            .headers(rateLimitHeaders("4321", "core")));

            retryClient.getAuthenticatedUser(TOKEN);

            assertThat(recorder.latest("core")).isNotNull();
            assertThat(recorder.latest("core").remaining()).isEqualTo(4321);
            retryServer.verify();
        }

        private static HttpHeaders rateLimitHeaders(String remaining, String resource) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(RateLimitSnapshot.HEADER_LIMIT, "5000");
            headers.add(RateLimitSnapshot.HEADER_REMAINING, remaining);
            headers.add(RateLimitSnapshot.HEADER_RESOURCE, resource);
            return headers;
        }

        @Test
        @DisplayName("4xx는 재시도하지 않는다")
        void doesNotRetryClientErrors() {
            server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                            requestTo(GithubTestClients.API_BASE_URL + "/user"))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

            assertThatThrownBy(() -> client.getAuthenticatedUser(TOKEN))
                    .isInstanceOf(GithubReauthRequiredException.class);

            server.verify();
        }
    }

    @Nested
    @DisplayName("user token 폐기")
    class TokenRevocation {

        private static final String REVOKE_URL =
                GithubTestClients.API_BASE_URL + "/applications/Iv1.testclient/token";

        private static final String EXPECTED_BASIC =
                "Basic SXYxLnRlc3RjbGllbnQ6dGVzdC1jbGllbnQtc2VjcmV0";

        @Test
        @DisplayName("client 자격증명으로 DELETE 하고 본문에 폐기할 토큰을 담는다")
        void revokesWithClientCredentials() {
            server.expect(requestTo(REVOKE_URL))
                    .andExpect(method(org.springframework.http.HttpMethod.DELETE))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, EXPECTED_BASIC))
                    .andExpect(content().json("{\"access_token\":\"" + TOKEN + "\"}"))
                    .andRespond(withStatus(HttpStatus.NO_CONTENT));

            client.revokeUserToken(TOKEN);

            server.verify();
        }

        @Test
        @DisplayName("user token을 Bearer로 보내지 않는다")
        void neverAuthenticatesWithUserToken() {
            server.expect(requestTo(REVOKE_URL))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, EXPECTED_BASIC))
                    .andRespond(withStatus(HttpStatus.NO_CONTENT));

            client.revokeUserToken(TOKEN);

            server.verify();
        }

        @Test
        @DisplayName("404는 이미 폐기된 토큰이므로 성공으로 본다")
        void treatsNotFoundAsAlreadyRevoked() {
            server.expect(requestTo(REVOKE_URL))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND)
                            .body("{\"message\":\"Not Found\"}")
                            .contentType(MediaType.APPLICATION_JSON));

            assertThatCode(() -> client.revokeUserToken(TOKEN)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("401은 App 자격증명 문제이므로 재인증 요구로 바꾸지 않는다")
        void doesNotMapUnauthorizedToReauth() {
            server.expect(requestTo(REVOKE_URL))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                            .body("{\"message\":\"Bad credentials\"}")
                            .contentType(MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.revokeUserToken(TOKEN))
                    .isInstanceOf(GithubApiException.class)
                    .isNotInstanceOf(GithubReauthRequiredException.class)
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.GITHUB_API_ERROR);
        }
    }

    @Nested
    @DisplayName("Link 헤더 페이지네이션")
    class Pagination {

        private static final String PAGE_1 = GithubTestClients.API_BASE_URL + "/user/repos";
        private static final String PAGE_2 = GithubTestClients.API_BASE_URL + "/user/repos?page=2";

        @Test
        @DisplayName("next 링크를 따라 전체 페이지를 모은다")
        void followsNextLinks() {
            server.expect(requestTo(PAGE_1))
                    .andRespond(withSuccess("[\"a\",\"b\"]", MediaType.APPLICATION_JSON)
                            .headers(linkHeader("<" + PAGE_2 + ">; rel=\"next\"")));
            server.expect(requestTo(PAGE_2))
                    .andRespond(withSuccess("[\"c\"]", MediaType.APPLICATION_JSON));

            List<String> all = client.getAllPages(
                    "/user/repos", TOKEN, new ParameterizedTypeReference<>() {
                    });

            assertThat(all).containsExactly("a", "b", "c");
            server.verify();
        }

        @Test
        @DisplayName("최대 페이지 수 상한에서 멈춘다")
        void stopsAtMaxPages() {
            initClient(GithubTestClients.properties(2, 1));

            server.expect(requestTo(PAGE_1))
                    .andRespond(withSuccess("[\"a\"]", MediaType.APPLICATION_JSON)
                            .headers(linkHeader("<" + PAGE_2 + ">; rel=\"next\"")));

            List<String> all = client.getAllPages(
                    "/user/repos", TOKEN, new ParameterizedTypeReference<>() {
                    });

            assertThat(all).containsExactly("a");
            server.verify();
        }

        private HttpHeaders linkHeader(String value) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.LINK, value);
            return headers;
        }
    }
}
