package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.GithubInstallationTokenClient;
import com.github.galpiii.galpi.domain.github.client.dto.GithubInstallationAccessTokenResponse;
import com.github.galpiii.galpi.domain.github.store.GithubInstallationTokenCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 저장소 범위를 좁힌 installation token을 캐시와 함께 내준다.
 *
 * <p>만료 직전 동시 요청으로 같은 범위의 토큰이 두 번 발급될 수 있다. 락을 걸지 않는다 —
 * 중복 발급의 대가는 호출 한 번이고, 락의 대가는 워커 사이의 분산 락 하나다. 두 토큰 모두
 * 정상 동작하므로 치명적이지 않다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubInstallationTokenService {

    /** 발급 응답의 만료가 이 여유보다 짧으면 캐시하지 않는다. 곧 만료될 값을 남길 이유가 없다. */
    private static final Duration MIN_CACHE_TTL = Duration.ofMinutes(1);

    /** 캐시 TTL은 만료 시각보다 이만큼 일찍 끝낸다. 쓰는 도중 만료되는 것을 막는다. */
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(5);

    private final GithubInstallationTokenClient tokenClient;
    private final GithubInstallationTokenCache tokenCache;

    /**
     * @param repositoryIds 이번 작업이 건드릴 GitHub 저장소 id. 이 집합이 토큰의 유효 범위이자
     *                      캐시 키의 일부다
     */
    public String issue(Long installationId, Collection<Long> repositoryIds) {
        List<Long> scope = GithubInstallationTokenCache.normalizeScope(repositoryIds);
        if (scope.isEmpty()) {
            throw new IllegalArgumentException("저장소 범위 없이 installation token을 발급할 수 없다");
        }

        Optional<String> cached = tokenCache.find(installationId, scope);
        if (cached.isPresent()) {
            return cached.get();
        }

        GithubInstallationAccessTokenResponse issued = tokenClient.issue(installationId, scope);
        Duration ttl = cacheTtl(issued.expiresAt());
        if (ttl.isZero()) {
            // 곧 만료될 토큰은 캐시하지 않는다. 넣어 두면 다음 요청이 만료 직전 값을 집는다.
            log.debug("[GitHub] 만료가 임박해 installation token을 캐시하지 않는다 installationId={}",
                    installationId);
        } else {
            tokenCache.put(installationId, scope, issued.token(), ttl);
        }
        log.debug("[GitHub] installation token 발급 installationId={} repositoryCount={}",
                installationId, scope.size());
        return issued.token();
    }

    /** 발급받은 토큰이 거부되면 캐시된 값을 버리고 다음 시도에서 새로 받게 한다. */
    public void invalidate(Long installationId, Collection<Long> repositoryIds) {
        tokenCache.evict(installationId, GithubInstallationTokenCache.normalizeScope(repositoryIds));
    }

    private Duration cacheTtl(OffsetDateTime expiresAt) {
        if (expiresAt == null) {
            return GithubInstallationTokenCache.TTL;
        }
        Duration remaining = Duration.between(OffsetDateTime.now(), expiresAt).minus(EXPIRY_MARGIN);
        if (remaining.compareTo(MIN_CACHE_TTL) < 0) {
            return Duration.ZERO;
        }
        return remaining.compareTo(GithubInstallationTokenCache.TTL) < 0
                ? remaining
                : GithubInstallationTokenCache.TTL;
    }
}
