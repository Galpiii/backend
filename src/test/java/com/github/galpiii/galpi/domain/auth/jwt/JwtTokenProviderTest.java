package com.github.galpiii.galpi.domain.auth.jwt;

import com.github.galpiii.galpi.domain.auth.config.JwtProperties;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JwtTokenProvider")
class JwtTokenProviderTest {

    private static final String SECRET = "galpi-test-secret-key-must-be-at-least-32-bytes";

    private JwtTokenProvider tokenProvider;

    private static JwtProperties properties(String secret, Duration accessTtl) {
        return properties(secret, "galpi", accessTtl);
    }

    private static JwtProperties properties(String secret, String issuer, Duration accessTtl) {
        return new JwtProperties(
                secret,
                issuer,
                accessTtl,
                Duration.ofDays(14),
                Duration.ofSeconds(60),
                new JwtProperties.Cookie("galpi_refresh", "/auth", true, "Lax", ""));
    }

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider(properties(SECRET, Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("발급한 Access 토큰을 다시 읽으면 userId가 보존된다")
    void roundTripsAccessToken() {
        String token = tokenProvider.createAccessToken(42L);

        TokenClaims claims = tokenProvider.parse(token, TokenType.ACCESS);

        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.type()).isEqualTo(TokenType.ACCESS);
        assertThat(claims.jti()).isNotBlank();
    }

    @Test
    @DisplayName("Refresh 토큰마다 jti가 달라 회전을 추적할 수 있다")
    void issuesUniqueJtiPerToken() {
        String first = tokenProvider.createRefreshToken(1L);
        String second = tokenProvider.createRefreshToken(1L);

        assertThat(tokenProvider.parse(first, TokenType.REFRESH).jti())
                .isNotEqualTo(tokenProvider.parse(second, TokenType.REFRESH).jti());
    }

    @Test
    @DisplayName("Refresh 토큰을 Access로 검증하면 거부한다")
    void rejectsTokenTypeMismatch() {
        String refreshToken = tokenProvider.createRefreshToken(1L);

        assertThatThrownBy(() -> tokenProvider.parse(refreshToken, TokenType.ACCESS))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("다른 키로 서명된 토큰을 거부한다")
    void rejectsForeignSignature() {
        JwtTokenProvider other = new JwtTokenProvider(
                properties("another-secret-key-that-is-long-enough-32", Duration.ofMinutes(30)));
        String foreignToken = other.createAccessToken(1L);

        assertThatThrownBy(() -> tokenProvider.parse(foreignToken, TokenType.ACCESS))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("만료된 토큰을 거부한다")
    void rejectsExpiredToken() {
        JwtTokenProvider shortLived = new JwtTokenProvider(
                properties(SECRET, Duration.ofSeconds(-1)));
        String expired = shortLived.createAccessToken(1L);

        assertThatThrownBy(() -> tokenProvider.parse(expired, TokenType.ACCESS))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.EXPIRED_TOKEN);
    }

    @Test
    @DisplayName("같은 시크릿이어도 issuer가 다르면 거부한다")
    void rejectsForeignIssuer() {
        JwtTokenProvider other = new JwtTokenProvider(
                properties(SECRET, "another-service", Duration.ofMinutes(30)));
        String foreignToken = other.createAccessToken(1L);

        assertThatThrownBy(() -> tokenProvider.parse(foreignToken, TokenType.ACCESS))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("변조된 토큰을 거부한다")
    void rejectsTamperedToken() {
        String token = tokenProvider.createAccessToken(1L);
        String tampered = token.substring(0, token.lastIndexOf('.')) + ".tampered";

        assertThatThrownBy(() -> tokenProvider.parse(tampered, TokenType.ACCESS))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("JWT 형식이 아닌 문자열을 거부한다")
    void rejectsGarbage() {
        assertThatThrownBy(() -> tokenProvider.parse("not-a-jwt", TokenType.ACCESS))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("32바이트 미만 시크릿이면 기동에 실패한다")
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new JwtTokenProvider(properties("too-short", Duration.ofMinutes(30))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
