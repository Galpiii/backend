package com.github.galpiii.galpi.domain.auth.jwt;

import com.github.galpiii.galpi.domain.auth.config.JwtProperties;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
public class JwtTokenProvider {

    private static final String CLAIM_TOKEN_TYPE = "typ";
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties properties;
    private final MACSigner signer;
    private final MACVerifier verifier;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "galpi.jwt.secret은 최소 " + MIN_SECRET_BYTES + "바이트여야 합니다. 현재 " + secret.length + "바이트");
        }
        try {
            this.signer = new MACSigner(secret);
            this.verifier = new MACVerifier(secret);
        } catch (JOSEException e) {
            throw new IllegalStateException("JWT 서명기 초기화에 실패했습니다.", e);
        }
    }

    public String createAccessToken(Long userId) {
        return create(userId, TokenType.ACCESS, properties.accessTokenTtl());
    }

    public String createRefreshToken(Long userId) {
        return create(userId, TokenType.REFRESH, properties.refreshTokenTtl());
    }

    private String create(Long userId, TokenType type, Duration ttl) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(userId))
                .issuer(properties.issuer())
                .jwtID(UUID.randomUUID().toString())
                .claim(CLAIM_TOKEN_TYPE, type.name())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("JWT 서명에 실패했습니다.", e);
        }
        return jwt.serialize();
    }

    public TokenClaims parse(String token, TokenType expectedType) {
        SignedJWT jwt;
        JWTClaimsSet claims;
        try {
            jwt = SignedJWT.parse(token);
            if (!jwt.verify(verifier)) {
                throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
            }
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException | JOSEException e) {
            log.debug("[JWT] 파싱 또는 서명 검증 실패: {}", e.getClass().getSimpleName());
            throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        }

        Date expiration = claims.getExpirationTime();
        if (expiration == null || expiration.toInstant().isBefore(Instant.now())) {
            throw new UnauthorizedException(ErrorCode.EXPIRED_TOKEN);
        }

        String type = claims.getClaim(CLAIM_TOKEN_TYPE) instanceof String s ? s : null;
        if (!expectedType.name().equals(type)) {
            log.debug("[JWT] 토큰 타입 불일치. expected={}", expectedType);
            throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        }

        Long userId;

        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            throw new UnauthorizedException(ErrorCode.INVALID_TOKEN);
        }

        return new TokenClaims(userId, claims.getJWTID(), expectedType, expiration.toInstant());
    }
}
