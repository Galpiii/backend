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
                .requestInterceptor(new GithubRetryInterceptor(properties.maxRetries()));
    }

    public RestClient.Builder oAuthClientBuilder(GithubAppProperties properties,
                                                 RateLimitRecorder rateLimitRecorder) {
        return baseBuilder(properties, rateLimitRecorder)
                .baseUrl(properties.oauthBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
    }

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
