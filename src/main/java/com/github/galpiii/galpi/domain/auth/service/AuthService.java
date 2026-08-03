package com.github.galpiii.galpi.domain.auth.service;

import com.github.galpiii.galpi.domain.auth.config.JwtProperties;
import com.github.galpiii.galpi.domain.auth.dto.IssuedTokens;
import com.github.galpiii.galpi.domain.auth.dto.MeResponse;
import com.github.galpiii.galpi.domain.auth.jwt.JwtTokenProvider;
import com.github.galpiii.galpi.domain.auth.jwt.TokenClaims;
import com.github.galpiii.galpi.domain.auth.jwt.TokenType;
import com.github.galpiii.galpi.domain.auth.store.LoginCodeStore;
import com.github.galpiii.galpi.domain.auth.store.RefreshTokenStore;
import com.github.galpiii.galpi.domain.github.service.GithubUserTokenService;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtTokenProvider tokenProvider;
    private final JwtProperties jwtProperties;
    private final RefreshTokenStore refreshTokenStore;
    private final LoginCodeStore loginCodeStore;
    private final GithubUserTokenService githubUserTokenService;
    private final UserRepository userRepository;

    public IssuedTokens exchangeLoginCode(String loginCode) {
        Long userId = loginCodeStore.consume(loginCode)
                .orElseThrow(() -> {
                    log.info("[Auth] 유효하지 않은 로그인 코드 교환 시도");
                    return new UnauthorizedException(ErrorCode.INVALID_LOGIN_CODE);
                });
        return issue(userId);
    }

    public IssuedTokens refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new UnauthorizedException(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }
        // 서명·만료·타입이 먼저 검증되므로, 여기를 통과했는데 저장소에 없다면 만료가 아니라
        // 이미 소비된 토큰이다. 즉 정상 회전이라면 나올 수 없는 요청이다.
        TokenClaims claims = tokenProvider.parse(refreshToken, TokenType.REFRESH);

        Long storedUserId = refreshTokenStore.consume(refreshToken)
                .orElseThrow(() -> onMissingToken(refreshToken, claims.userId()));

        if (!storedUserId.equals(claims.userId())) {
            log.warn("[Auth] refresh 토큰의 주체가 저장값과 다르다");
            throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        }
        return issue(storedUserId);
    }

    /**
     * 살아 있는 토큰인데 저장소에 없는 경우는 둘 중 하나다. 로그아웃으로 우리가 지웠거나,
     * 탈취된 토큰이 재사용됐거나. 후자면 어느 쪽이 진짜 사용자인지 알 수 없으므로
     * 그 사용자의 모든 세션을 끊고 다시 로그인하게 한다.
     */
    private UnauthorizedException onMissingToken(String refreshToken, Long userId) {
        if (refreshTokenStore.wasRevoked(refreshToken)) {
            log.info("[Auth] 이미 폐기된 refresh 토큰으로 갱신 시도 userId={}", userId);
            return new UnauthorizedException(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }

        int revoked = refreshTokenStore.revokeAll(userId);
        log.warn("[Auth] refresh 토큰 재사용 감지. 모든 세션을 폐기했다 userId={} 폐기={}", userId, revoked);
        return new UnauthorizedException(ErrorCode.REFRESH_TOKEN_REUSED);
    }

    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshTokenStore.revoke(refreshToken);
    }

    @Transactional(readOnly = true)
    public MeResponse getMe(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHORIZED));
        return MeResponse.of(user, githubUserTokenService.isValid(userId));
    }

    private IssuedTokens issue(Long userId) {
        String accessToken = tokenProvider.createAccessToken(userId);
        String refreshToken = tokenProvider.createRefreshToken(userId);
        refreshTokenStore.save(refreshToken, userId, jwtProperties.refreshTokenTtl());
        return new IssuedTokens(accessToken, refreshToken, jwtProperties.accessTokenTtl().toSeconds());
    }
}
