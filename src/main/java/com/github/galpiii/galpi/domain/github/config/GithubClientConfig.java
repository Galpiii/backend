package com.github.galpiii.galpi.domain.github.config;

import com.github.galpiii.galpi.domain.github.client.GithubLoggingInterceptor;
import com.github.galpiii.galpi.domain.github.client.GithubRetryInterceptor;
import com.github.galpiii.galpi.domain.github.client.RateLimitRecorder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class GithubClientConfig {

    public static final String API_CLIENT = "githubApiRestClient";
    public static final String OAUTH_CLIENT = "githubOAuthRestClient";

    private static final String GITHUB_ACCEPT = "application/vnd.github+json";
    private static final String API_VERSION_HEADER = "X-GitHub-Api-Version";

    @Bean(API_CLIENT)
    public RestClient githubApiRestClient(GithubAppProperties properties,
                                          RateLimitRecorder rateLimitRecorder) {
        return apiClientBuilder(properties, rateLimitRecorder).build();
    }

    @Bean(OAUTH_CLIENT)
    public RestClient githubOAuthRestClient(GithubAppProperties properties,
                                            RateLimitRecorder rateLimitRecorder) {
        return oAuthClientBuilder(properties, rateLimitRecorder).build();
    }

    public RestClient.Builder apiClientBuilder(GithubAppProperties properties,
                                               RateLimitRecorder rateLimitRecorder) {
        return baseBuilder(properties, rateLimitRecorder)
                .baseUrl(properties.apiBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, GITHUB_ACCEPT)
                .defaultHeader(API_VERSION_HEADER, properties.apiVersion())
                // 이 클라이언트가 보내는 건 GET과 DELETE뿐이라 재시도해도 안전하다.
                .requestInterceptor(new GithubRetryInterceptor(properties.maxRetries()));
    }

    /**
     * OAuth 클라이언트에는 일부러 재시도를 붙이지 않는다. 보내는 요청이 code 교환 POST 하나인데
     * code는 일회용이라, 5xx 뒤 재시도해도 GitHub이 이미 code를 소비했다면 확정 실패다.
     * 얻는 것 없이 사용자 대기 시간만 늘어난다.
     */
    public RestClient.Builder oAuthClientBuilder(GithubAppProperties properties,
                                                 RateLimitRecorder rateLimitRecorder) {
        return baseBuilder(properties, rateLimitRecorder)
                .baseUrl(properties.oauthBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
    }

    /**
     * 재시도 인터셉터는 반드시 로깅 인터셉터보다 <b>뒤에</b> 등록해야 한다.
     * <p>
     * Spring의 인터셉터 체인은 요청 하나당 iterator를 하나만 만들어 공유한다. 그래서 재시도가
     * {@code execution.execute()}를 두 번째로 부르는 순간 iterator는 이미 소진돼 있고, 뒤쪽
     * 인터셉터를 전부 건너뛴 채 실제 요청으로 직행한다. 재시도가 앞에 있으면 로깅은 첫 5xx만 보고
     * 정작 최종 응답의 rate limit 헤더를 놓친다. 순서를 뒤집어 재시도를 체인 맨 끝에 두면,
     * 로깅이 재시도까지 끝난 최종 응답을 기록한다.
     */
    private RestClient.Builder baseBuilder(GithubAppProperties properties,
                                           RateLimitRecorder rateLimitRecorder) {
        return RestClient.builder()
                .requestFactory(requestFactory(properties))
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .requestInterceptor(new GithubLoggingInterceptor(rateLimitRecorder));
    }

    private JdkClientHttpRequestFactory requestFactory(GithubAppProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }
}
