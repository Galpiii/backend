package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.dto.GithubAccessTokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class GithubTokenExpiryMonitor {

    private final AtomicBoolean expirationDisabled = new AtomicBoolean(false);
    private final AtomicBoolean warned = new AtomicBoolean(false);

    public void inspect(GithubAccessTokenResponse response) {
        if (response.hasExpiry()) {
            expirationDisabled.set(false);
            return;
        }

        expirationDisabled.set(true);
        if (warned.compareAndSet(false, true)) {
            log.warn("""
                    [GitHub] user access token 응답에 expires_in이 없습니다.
                    GitHub App의 'Expire user authorization tokens'가 비활성화된 것으로 보입니다.
                    현재 토큰은 무기한 유효하며, 갈피는 세션 수명으로만 TTL을 제한합니다.
                    App 설정 > Optional Features에서 만료를 활성화하세요.""");
        } else {
            log.warn("[GitHub] expires_in 부재가 반복되고 있습니다. App 만료 설정을 확인하세요.");
        }
    }

    public boolean isExpirationDisabled() {
        return expirationDisabled.get();
    }
}
