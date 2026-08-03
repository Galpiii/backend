package com.github.galpiii.galpi.domain.github.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GithubTokenRevocationScheduler {

    private final GithubTokenRevoker tokenRevoker;

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void retryPendingRevocations() {
        try {
            tokenRevoker.retryPending();
        } catch (RuntimeException e) {
            log.error("[GitHub] 밀린 토큰 폐기 재시도 배치가 실패했다", e);
        }
    }
}
