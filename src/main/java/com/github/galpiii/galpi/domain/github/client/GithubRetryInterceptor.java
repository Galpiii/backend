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
public class GithubRetryInterceptor implements ClientHttpRequestInterceptor {

    private static final long BASE_BACKOFF_MILLIS = 200;

    private final int maxRetries;

    @Override
    public ClientHttpResponse intercept(HttpRequest request,
                                        byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        consumeBudget(request);
        ClientHttpResponse response = execution.execute(request, body);

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (!response.getStatusCode().is5xxServerError()) {
                return response;
            }
            response.close();
            sleepBackoff(attempt);
            log.debug("[GitHub] 5xx 재시도 {}/{} uri={}",
                    attempt, maxRetries, TokenMasker.mask(request.getURI().toString()));
            consumeBudget(request);
            response = execution.execute(request, body);
        }
        return response;
    }

    private static void consumeBudget(HttpRequest request) {
        Object value = request.getAttributes().get(GithubRequestBudget.REQUEST_ATTRIBUTE);
        if (value instanceof GithubRequestBudget budget) {
            budget.consume();
        }
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(BASE_BACKOFF_MILLIS * (1L << (attempt - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재시도 대기 중 인터럽트", e);
        }
    }
}
