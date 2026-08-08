package com.github.galpiii.galpi.domain.github.appjwt;

import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Component
public class GithubAppJwtGenerator {

    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    private static final Duration LIFETIME = Duration.ofMinutes(9);

    private final String appId;
    private final RSASSASigner signer;

    public GithubAppJwtGenerator(GithubAppProperties properties) {
        this.appId = properties.appId();
        RSAPrivateKey privateKey = PemPrivateKeyParser.parse(properties.normalizedPrivateKey());
        this.signer = new RSASSASigner(privateKey);
    }

    public String generate() {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(appId)
                .issueTime(Date.from(now.minus(CLOCK_SKEW)))
                .expirationTime(Date.from(now.plus(LIFETIME)))
                .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("App JWT 서명에 실패했습니다: " + e.getClass().getSimpleName());
        }
        return jwt.serialize();
    }
}
