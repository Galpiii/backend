package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.auth.config.JwtProperties;
import com.github.galpiii.galpi.domain.github.exception.GithubReauthRequiredException;
import com.github.galpiii.galpi.domain.github.store.GithubUserTokenCache;
import com.github.galpiii.galpi.domain.user.entity.OAuthProvider;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.entity.UserOAuthToken;
import com.github.galpiii.galpi.domain.user.repository.UserOAuthTokenRepository;
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
    private final GithubUserTokenCache cache;
    private final TokenCipher tokenCipher;
    private final JwtProperties jwtProperties;

    @Transactional
    public void save(User user, String accessToken, Duration expiresIn) {
        OffsetDateTime expiresAt = expiresIn == null ? null : OffsetDateTime.now().plus(expiresIn);
        String encrypted = tokenCipher.encrypt(accessToken);
        int version = tokenCipher.currentVersion();

        tokenRepository.findByUserIdAndProvider(user.getId(), PROVIDER)
                .ifPresentOrElse(
                        existing -> existing.replace(encrypted, expiresAt, version),
                        () -> tokenRepository.save(
                                UserOAuthToken.issue(user, PROVIDER, encrypted, expiresAt, version)));

        cacheAfterCommit(user.getId(), accessToken, cacheTtl(expiresIn));
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

    @Transactional(readOnly = true)
    public Optional<String> find(Long userId) {
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

    @Transactional
    public void delete(Long userId) {
        cache.evict(userId);
        tokenRepository.deleteByUserIdAndProvider(userId, PROVIDER);
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
