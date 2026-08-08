package com.github.galpiii.galpi.domain.github.client;

import com.github.galpiii.galpi.global.util.TokenMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
public class GithubLoggingInterceptor implements ClientHttpRequestInterceptor {

    private final RateLimitRecorder rateLimitRecorder;

    @Override
    public ClientHttpResponse intercept(HttpRequest request,
                                        byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String safeUri = TokenMasker.mask(request.getURI().toString());
        long startedAt = System.nanoTime();

        try {
            ClientHttpResponse response = execution.execute(request, body);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            rateLimitRecorder.record(RateLimitSnapshot.from(response.getHeaders()));
            log.debug("[GitHub] {} {} -> {} ({}ms)",
                    request.getMethod(), safeUri, response.getStatusCode().value(), elapsedMs);
            return response;
        } catch (IOException e) {
            log.warn("[GitHub] {} {} 실패: {}",
                    request.getMethod(), safeUri, TokenMasker.mask(String.valueOf(e.getMessage())));
            throw e;
        }
    }
}
