package com.github.galpiii.galpi.domain.github.client;

import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.config.GithubClientConfig;
import com.github.galpiii.galpi.domain.github.config.GithubOperationProperties;

import java.time.Duration;
import java.util.List;

final class GithubTestClients {

    static final String API_BASE_URL = "https://api.github.com";
    static final String OAUTH_BASE_URL = "https://github.com";
    static final String API_VERSION = "2022-11-28";

    private GithubTestClients() {
    }

    static GithubAppProperties properties() {
        return properties(2, 10);
    }

    static GithubAppProperties properties(int maxRetries, int maxPages) {
        return new GithubAppProperties(
                "12345",
                "galpi-app",
                "Iv1.testclient",
                "test-client-secret",
                "-----BEGIN PRIVATE KEY-----\nunused\n-----END PRIVATE KEY-----",
                "https://api.galpi.dev",
                API_VERSION,
                API_BASE_URL,
                OAUTH_BASE_URL,
                "Galpi",
                List.of("https://galpi.dev"),
                "https://galpi.dev/auth/callback",
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                maxRetries,
                maxPages);
    }

    static GithubClientConfig config() {
        return new GithubClientConfig();
    }

    static GithubOperationProperties operationProperties() {
        return operationProperties(50);
    }

    static GithubOperationProperties operationProperties(int maxRequests) {
        return new GithubOperationProperties(maxRequests, Duration.ofSeconds(30), 1);
    }
}
