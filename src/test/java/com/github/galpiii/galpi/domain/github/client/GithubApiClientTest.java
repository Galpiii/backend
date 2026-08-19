package com.github.galpiii.galpi.domain.github.client;

import com.github.galpiii.galpi.domain.github.client.dto.GithubInstallationResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubUserResponse;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.domain.github.exception.GithubInstallationUnavailableException;
import com.github.galpiii.galpi.domain.github.exception.GithubRateLimitedException;
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
        initClient(properties, GithubTestClients.operationProperties());
    }

    private void initClient(GithubAppProperties properties,
                            com.github.galpiii.galpi.domain.github.config.GithubOperationProperties
                                    operationProperties) {
        RestClient.Builder builder = GithubTestClients.config()
                .apiClientBuilder(properties, new RateLimitRecorder());
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GithubApiClient(builder.build(), properties, operationProperties);
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

        @Test
        @DisplayName("installation 정지 상태를 파싱한다")
        void parsesSuspendedInstallation() {
            server.expect(requestTo(GithubTestClients.API_BASE_URL
                            + "/user/installations?per_page=100"))
                    .andRespond(withSuccess("""
                            {"total_count":1,"installations":[
                              {"id":100,"account":{"id":1,"login":"wb","type":"User"},
                               "repository_selection":"selected",
                               "suspended_at":"2026-08-17T01:00:00Z"}]}
                            """, MediaType.APPLICATION_JSON));

            GithubInstallationResponse installation =
                    client.getUserInstallations(TOKEN, client.newOperationBudget()).getFirst();

            assertThat(installation.isSuspended()).isTrue();
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
                    .isInstanceOf(GithubRateLimitedException.class)
                    .hasFieldOrPropertyWithValue("retryAfterSeconds", 60L)
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
        @DisplayName("Retry-After가 없어도 응답 본문이 secondary rate limit이면 429로 분류한다")
        void mapsSecondaryRateLimitWithoutRetryAfter() {
            server.expect(requestTo(GithubTestClients.API_BASE_URL + "/user"))
                    .andRespond(withStatus(HttpStatus.FORBIDDEN)
                            .header(RateLimitSnapshot.HEADER_REMAINING, "4998")
                            .body("""
                                    {"message":"You have exceeded a secondary rate limit."}
                                    """)
                            .contentType(MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.getAuthenticatedUser(TOKEN))
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.GITHUB_RATE_LIMITED);
        }

        @Test
        @DisplayName("목록 조회 사이에 사라진 installation의 404를 구분한다")
        void distinguishesUnavailableInstallation() {
            server.expect(requestTo(GithubTestClients.API_BASE_URL
                            + "/user/installations/100/repositories?per_page=100"))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND)
                            .body("{\"message\":\"Not Found\"}")
                            .contentType(MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.getInstallationRepositories(
                    TOKEN, 100L, client.newOperationBudget()))
                    .isInstanceOf(GithubInstallationUnavailableException.class);
        }

        @Test
        @DisplayName("목록 조회 직후 정지된 installation의 403을 구분한다")
        void distinguishesInstallationSuspendedDuringListing() {
            server.expect(requestTo(GithubTestClients.API_BASE_URL
                            + "/user/installations/100/repositories?per_page=100"))
                    .andRespond(withStatus(HttpStatus.FORBIDDEN)
                            .body("{\"message\":\"This installation has been suspended\"}")
                            .contentType(MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.getInstallationRepositories(
                    TOKEN, 100L, client.newOperationBudget()))
                    .isInstanceOf(GithubInstallationUnavailableException.class);
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
            GithubApiClient retryClient = new GithubApiClient(
                    builder.build(), properties, GithubTestClients.operationProperties());

            retryServer.expect(requestTo(GithubTestClients.API_BASE_URL + "/user"))
                    .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
            retryServer.expect(requestTo(GithubTestClients.API_BASE_URL + "/user"))
                    .andRespond(withSuccess("{\"id\":1,\"login\":\"octocat\"}", MediaType.APPLICATION_JSON)
                            .headers(rateLimitHeaders("4321", "core")));

            retryClient.getAuthenticatedUser(TOKEN);

            assertThat(recorder.latest(TOKEN, "core")).isNotNull();
            assertThat(recorder.latest(TOKEN, "core").remaining()).isEqualTo(4321);
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

        @Test
        @DisplayName("5xx 재시도도 사용자 작업의 요청 budget을 소비한다")
        void countsRetriesAgainstOperationBudget() {
            initClient(GithubTestClients.properties(), GithubTestClients.operationProperties(1));
            server.expect(requestTo(GithubTestClients.API_BASE_URL + "/user"))
                    .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

            assertThatThrownBy(() -> client.getAuthenticatedUser(TOKEN))
                    .isInstanceOf(GithubApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.GITHUB_OPERATION_BUDGET_EXCEEDED);
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
                    }, client.newOperationBudget());

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
                    }, client.newOperationBudget());

            assertThat(all).containsExactly("a");
            server.verify();
        }

        @Test
        @DisplayName("화면용 조회는 API 호출 budget 소진 시 수집한 페이지를 반환한다")
        void returnsPartialResultWhenOperationBudgetIsExhausted() {
            initClient(GithubTestClients.properties(), GithubTestClients.operationProperties(1));

            server.expect(requestTo(PAGE_1))
                    .andRespond(withSuccess("[\"a\"]", MediaType.APPLICATION_JSON)
                            .headers(linkHeader("<" + PAGE_2 + ">; rel=\"next\"")));

            List<String> all = client.getAllPages(
                    "/user/repos", TOKEN, new ParameterizedTypeReference<List<String>>() {
                    }, client.newOperationBudget());

            assertThat(all).containsExactly("a");
            server.verify();
        }

        @Test
        @DisplayName("권한 판정용 조회는 API 호출 budget 소진 시 하드 실패한다")
        void enforcesOperationBudgetForPermissionChecks() {
            initClient(GithubTestClients.properties(), GithubTestClients.operationProperties(1));

            String firstPage = GithubTestClients.API_BASE_URL + "/user/installations?per_page=100";
            server.expect(requestTo(firstPage))
                    .andRespond(withSuccess("""
                            {"total_count":2,"installations":[
                              {"id":100,"account":{"id":1,"login":"wb","type":"User"},
                               "repository_selection":"selected"}]}""",
                            MediaType.APPLICATION_JSON)
                            .headers(linkHeader("<" + firstPage + "&page=2>; rel=\"next\"")));

            assertThatThrownBy(() -> client.getUserInstallationsComplete(
                    TOKEN, client.newOperationBudget()))
                    .isInstanceOf(GithubApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.GITHUB_OPERATION_BUDGET_EXCEEDED);
            server.verify();
        }

        @Test
        @DisplayName("권한 판정용 조회는 상한에 걸리면 잘린 목록 대신 예외를 낸다")
        void failsInsteadOfReturningTruncatedListForPermissionChecks() {
            initClient(GithubTestClients.properties(2, 1));

            String firstPage = GithubTestClients.API_BASE_URL + "/user/installations?per_page=100";
            server.expect(requestTo(firstPage))
                    .andRespond(withSuccess("""
                            {"total_count":2,"installations":[
                              {"id":100,"account":{"id":1,"login":"wb","type":"User"},
                               "repository_selection":"selected"}]}""",
                            MediaType.APPLICATION_JSON)
                            .headers(linkHeader("<" + firstPage + "&page=2>; rel=\"next\"")));

            assertThatThrownBy(() -> client.getUserInstallationsComplete(
                    TOKEN, client.newOperationBudget()))
                    .isInstanceOf(GithubApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.GITHUB_REPOSITORY_LIST_INCOMPLETE);
            server.verify();
        }

        @Test
        @DisplayName("Link가 API 오리진 밖을 가리키면 따라가지 않는다 — Bearer 토큰이 외부로 나가면 안 된다")
        void refusesToFollowOffOriginNextLink() {
            server.expect(requestTo(PAGE_1))
                    .andRespond(withSuccess("[\"a\"]", MediaType.APPLICATION_JSON)
                            .headers(linkHeader("<https://evil.example/user/repos?page=2>; rel=\"next\"")));

            List<String> all = client.getAllPages(
                    "/user/repos", TOKEN, new ParameterizedTypeReference<>() {
                    }, client.newOperationBudget());

            assertThat(all).containsExactly("a");
            // 두 번째 요청이 나갔다면 MockRestServiceServer가 예상치 못한 호출로 실패시킨다.
            server.verify();
        }

        @Test
        @DisplayName("프로토콜 상대 URL로도 오리진을 벗어날 수 없다")
        void refusesProtocolRelativeLink() {
            server.expect(requestTo(PAGE_1))
                    .andRespond(withSuccess("[\"a\"]", MediaType.APPLICATION_JSON)
                            .headers(linkHeader("<//evil.example/user/repos?page=2>; rel=\"next\"")));

            assertThat(client.getAllPages(
                    "/user/repos", TOKEN, new ParameterizedTypeReference<List<String>>() {
                    }, client.newOperationBudget())).containsExactly("a");
            server.verify();
        }

        @Test
        @DisplayName("기본 포트를 명시한 같은 오리진은 막지 않는다")
        void followsSameOriginWithExplicitDefaultPort() {
            server.expect(requestTo(PAGE_1))
                    .andRespond(withSuccess("[\"a\"]", MediaType.APPLICATION_JSON)
                            .headers(linkHeader(
                                    "<https://api.github.com:443/user/repos?page=2>; rel=\"next\"")));
            server.expect(requestTo("https://api.github.com:443/user/repos?page=2"))
                    .andRespond(withSuccess("[\"b\"]", MediaType.APPLICATION_JSON));

            assertThat(client.getAllPages(
                    "/user/repos", TOKEN, new ParameterizedTypeReference<List<String>>() {
                    }, client.newOperationBudget())).containsExactly("a", "b");
            server.verify();
        }

        @Test
        @DisplayName("호스트만 비슷한 곳도 막는다")
        void refusesLookalikeHost() {
            server.expect(requestTo(PAGE_1))
                    .andRespond(withSuccess("[\"a\"]", MediaType.APPLICATION_JSON)
                            .headers(linkHeader(
                                    "<https://api.github.com.evil.example/user/repos>; rel=\"next\"")));

            assertThat(client.getAllPages(
                    "/user/repos", TOKEN, new ParameterizedTypeReference<List<String>>() {
                    }, client.newOperationBudget())).containsExactly("a");
            server.verify();
        }

        @Test
        @DisplayName("같은 오리진의 절대 URL은 그대로 따라간다")
        void followsAbsoluteUrlOnSameOrigin() {
            server.expect(requestTo(PAGE_1))
                    .andRespond(withSuccess("[\"a\"]", MediaType.APPLICATION_JSON)
                            .headers(linkHeader("<" + PAGE_2 + ">; rel=\"next\"")));
            server.expect(requestTo(PAGE_2))
                    .andRespond(withSuccess("[\"b\"]", MediaType.APPLICATION_JSON));

            assertThat(client.getAllPages(
                    "/user/repos", TOKEN, new ParameterizedTypeReference<List<String>>() {
                    }, client.newOperationBudget())).containsExactly("a", "b");
            server.verify();
        }

        private HttpHeaders linkHeader(String value) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.LINK, value);
            return headers;
        }
    }
}
