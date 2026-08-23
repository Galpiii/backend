package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.auth.config.JwtProperties;
import com.github.galpiii.galpi.domain.github.exception.GithubReauthRequiredException;
import com.github.galpiii.galpi.domain.github.store.GithubUserTokenCache;
import com.github.galpiii.galpi.domain.user.entity.GithubConnectionStatus;
import com.github.galpiii.galpi.domain.user.entity.OAuthProvider;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.entity.UserOAuthToken;
import com.github.galpiii.galpi.domain.user.repository.UserOAuthTokenRepository;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.crypto.TokenCipher;
import com.github.galpiii.galpi.global.crypto.TokenCipherException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GithubUserTokenService {

    private static final OAuthProvider PROVIDER = OAuthProvider.GITHUB;
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(1);
    private final UserOAuthTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final GithubUserTokenCache cache;
    private final TokenCipher tokenCipher;
    private final JwtProperties jwtProperties;
    private final GithubTokenRevoker tokenRevoker;

    /**
     * 새로 받은 user access token을 원본으로 저장한다.
     *
     * <p>기존 행이 있으면 덮어쓰는데, 그 전에 이전 암호문을 폐기 큐로 옮긴다. 재로그인은
     * 이전 토큰을 무효화하지 않아서 GitHub에는 최대 만료 시각까지 그대로 살아 있고, 암호문을
     * 덮어쓰고 나면 복호화할 원본이 없어 회수할 수단 자체가 사라진다. 폐기 호출을 큐에 넘기는
     * 것은 로그인을 GitHub 응답만큼 느리게 만들지 않기 위해서다.
     */
    @Transactional
    public void save(User user, String accessToken, Duration expiresIn) {
        OffsetDateTime expiresAt = expiresIn == null ? null : OffsetDateTime.now().plus(expiresIn);
        String encrypted = tokenCipher.encrypt(accessToken);
        int version = tokenCipher.currentVersion();

        tokenRepository.findByUserIdAndProvider(user.getId(), PROVIDER)
                .ifPresentOrElse(
                        existing -> {
                            enqueueSupersededRevocation(user.getId(), existing);
                            existing.replace(encrypted, expiresAt, version);
                        },
                        () -> tokenRepository.save(
                                UserOAuthToken.issue(user, PROVIDER, encrypted, expiresAt, version)));

        cacheAfterCommit(user.getId(), accessToken, cacheTtl(expiresIn));
    }

    /**
     * 덮어쓰기 직전의 암호문을 폐기 큐로 넘긴다. 반드시 {@code replace} 전에 읽어야 한다.
     *
     * <p>이미 만료된 토큰은 넘기지 않는다. GitHub이 어차피 거부할 호출이라 큐만 쌓인다.
     */
    private void enqueueSupersededRevocation(Long userId, UserOAuthToken existing) {
        if (existing.isExpired()) {
            return;
        }
        tokenRevoker.enqueueSuperseded(
                userId, existing.getEncryptedAccessToken(), existing.getTokenVersion());
    }

    /**
     * 캐시를 비운다. 커밋 <b>직전</b>에 한 번, 트랜잭션이 끝난 뒤에 한 번.
     *
     * <p>앞의 것이 실패를 알리는 자리다. {@code afterCompletion}에서 던진 예외는 트랜잭션
     * 매니저가 로그만 남기고 삼키므로, 거기에만 두면 "비우지 못했다"는 사실이 호출자에게 닿지
     * 않는다. 그러면 원본은 지워졌는데 캐시에는 살아 있는 토큰이 TTL이 끝날 때까지 남고, 해제된
     * 사용자가 계속 GitHub을 부를 수 있다. {@code beforeCommit}에서 비우면 그 실패가 그대로
     * 롤백으로 이어져 아무것도 지워지지 않은 상태로 되돌아간다.
     *
     * <p>뒤의 것은 그 사이의 경합을 덮는다. 커밋 전에는 원본이 아직 살아 있어서, 그 짧은 구간에
     * 들어온 조회가 캐시를 다시 채울 수 있다. 이쪽은 되돌릴 것이 없으므로 로그만 남긴다.
     */
    private void evictAroundCommit(Long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cache.evict(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                cache.evict(userId);
            }

            @Override
            public void afterCompletion(int status) {
                // 커밋이든 롤백이든 비운다. 롤백이면 원본이 그대로 남아 다음 조회가 다시
                // 채우므로 손해가 없고, 커밋이면 반드시 비어 있어야 한다.
                evictQuietly(userId);
            }
        });
    }

    private void evictQuietly(Long userId) {
        try {
            cache.evict(userId);
        } catch (RuntimeException e) {
            log.error("[GitHub] 커밋 후 캐시 무효화에 실패했다. 커밋 직전에 비운 뒤 다시 채워졌다면 "
                            + "무효가 된 토큰이 TTL까지 남는다 userId={} cause={}",
                    userId, e.getClass().getSimpleName());
        }
    }

    private void cacheAfterCommit(Long userId, String accessToken, Duration ttl) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cache.put(userId, accessToken, ttl);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.put(userId, accessToken, ttl);
            }
        });
    }

    public String require(Long userId) {
        return find(userId).orElseThrow(GithubReauthRequiredException::new);
    }

    /**
     * 이 사용자로 GitHub을 부를 토큰을 꺼낸다.
     *
     * <p>연결 상태를 먼저 본다. 캐시는 정본이 아니고 비우기가 실패할 수도 있어서, 토큰만 가지고
     * 접근을 허락하면 해제된 사용자가 캐시에 남은 토큰으로 계속 GitHub을 부를 수 있다. 연결
     * 상태는 원본을 지운 그 트랜잭션에서 함께 바뀌므로, 캐시가 스테일해도 이 검사는 스테일하지
     * 않다.
     */
    @Transactional(readOnly = true)
    public Optional<String> find(Long userId) {
        if (!userRepository.existsByIdAndGithubConnectionStatus(
                userId, GithubConnectionStatus.CONNECTED)) {
            return Optional.empty();
        }

        Optional<String> cached = cache.find(userId);
        if (cached.isPresent()) {
            return cached;
        }

        Optional<UserOAuthToken> stored = tokenRepository.findByUserIdAndProvider(userId, PROVIDER);
        if (stored.isEmpty()) {
            return Optional.empty();
        }

        UserOAuthToken token = stored.get();
        if (token.isExpired()) {
            log.info("[GitHub] user access token 만료. 재인증이 필요 userId={}", userId);
            return Optional.empty();
        }

        String plaintext;
        try {
            plaintext = tokenCipher.decrypt(token.getEncryptedAccessToken(), token.getTokenVersion());
        } catch (TokenCipherException e) {
            log.warn("[GitHub] 저장된 토큰을 복호화하지 못함 userId={} tokenVersion={}",
                    userId, token.getTokenVersion());
            return Optional.empty();
        }

        cache.put(userId, plaintext, remainingTtl(token.getAccessTokenExpiresAt()));
        return Optional.of(plaintext);
    }

    public boolean isValid(Long userId) {
        return find(userId).isPresent();
    }

    /**
     * 원본을 지운다. 캐시는 커밋 경계에 맞춰 비운다.
     *
     * <p>지금 당장 비우지 않는 것은, 커밋 전에 다른 요청이 아직 살아 있는 원본을 읽어 캐시를
     * 다시 채울 수 있어서다. 그러면 행이 지워진 뒤에도 캐시에 유효한 토큰이 남아 연결 해제가
     * 반쪽이 된다. 비우는 시점과 실패 처리는 {@link #evictAroundCommit(Long)}에 있다.
     */
    @Transactional
    public void delete(Long userId) {
        tokenRepository.deleteByUserIdAndProvider(userId, PROVIDER);
        evictAroundCommit(userId);
    }

    private Duration cacheTtl(Duration expiresIn) {
        Duration sessionTtl = jwtProperties.refreshTokenTtl();
        if (expiresIn == null) {
            return sessionTtl;
        }
        Duration githubTtl = expiresIn.minus(EXPIRY_MARGIN);
        return githubTtl.compareTo(sessionTtl) < 0 ? githubTtl : sessionTtl;
    }

    private Duration remainingTtl(OffsetDateTime expiresAt) {
        if (expiresAt == null) {
            return jwtProperties.refreshTokenTtl();
        }
        return cacheTtl(Duration.between(OffsetDateTime.now(), expiresAt));
    }
}
