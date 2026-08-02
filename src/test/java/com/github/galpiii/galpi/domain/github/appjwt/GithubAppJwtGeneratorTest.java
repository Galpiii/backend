package com.github.galpiii.galpi.domain.github.appjwt;

import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GithubAppJwtGenerator")
class GithubAppJwtGeneratorTest {

    private static final String APP_ID = "123456";

    private static KeyPair keyPair;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    /** JDK가 내보내는 PKCS#8 PEM. */
    private static String pkcs8Pem() {
        return wrap("PRIVATE KEY", keyPair.getPrivate().getEncoded());
    }

    /**
     * GitHub이 실제로 내려주는 PKCS#1 PEM. JDK는 PKCS#1을 직접 내보내지 못하므로
     * RSAPrivateCrtKey에서 DER을 조립한다.
     */
    private static String pkcs1Pem() {
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) keyPair.getPrivate();
        byte[] der = Pkcs1Der.encode(key);
        return wrap("RSA PRIVATE KEY", der);
    }

    private static String wrap(String label, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----";
    }

    private static GithubAppProperties propertiesWith(String privateKey) {
        return new GithubAppProperties(
                APP_ID, "Iv1.client", "secret", privateKey, "https://api.galpi.dev",
                "2022-11-28", "https://api.github.com", "https://github.com", "Galpi",
                List.of("https://galpi.dev"), "https://galpi.dev/auth/callback",
                Duration.ofSeconds(5), Duration.ofSeconds(15), 2, 10);
    }

    @Test
    @DisplayName("PKCS#8 개인키로 RS256 JWT를 서명한다")
    void signsWithPkcs8Key() throws Exception {
        SignedJWT jwt = SignedJWT.parse(
                new GithubAppJwtGenerator(propertiesWith(pkcs8Pem())).generate());

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) keyPair.getPublic()))).isTrue();
    }

    @Test
    @DisplayName("GitHub이 내려주는 PKCS#1 개인키도 그대로 읽는다")
    void signsWithPkcs1Key() throws Exception {
        SignedJWT jwt = SignedJWT.parse(
                new GithubAppJwtGenerator(propertiesWith(pkcs1Pem())).generate());

        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) keyPair.getPublic()))).isTrue();
    }

    @Test
    @DisplayName("환경변수에서 \\n으로 이스케이프된 개행을 복원한다")
    void restoresEscapedNewlines() throws Exception {
        String escaped = pkcs8Pem().replace("\n", "\\n");

        SignedJWT jwt = SignedJWT.parse(
                new GithubAppJwtGenerator(propertiesWith(escaped)).generate());

        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) keyPair.getPublic()))).isTrue();
    }

    @Test
    @DisplayName("iss는 App ID, iat는 60초 전, exp는 10분 이내다")
    void setsRequiredClaims() throws Exception {
        Instant now = Instant.now();

        SignedJWT jwt = SignedJWT.parse(
                new GithubAppJwtGenerator(propertiesWith(pkcs8Pem())).generate());

        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo(APP_ID);
        assertThat(jwt.getJWTClaimsSet().getIssueTime().toInstant())
                .isBefore(now)
                .isAfter(now.minusSeconds(120));
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant())
                .isAfter(now)
                .isBefore(now.plusSeconds(600));
    }

    @Test
    @DisplayName("PEM이 아니면 기동 단계에서 실패한다")
    void failsFastOnInvalidPem() {
        assertThatThrownBy(() -> new GithubAppJwtGenerator(propertiesWith("not-a-pem")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCS");
    }

    @Test
    @DisplayName("실패 메시지에 키 내용이 섞이지 않는다")
    void neverLeaksKeyMaterialInErrors() {
        String corrupted = "-----BEGIN PRIVATE KEY-----\nQUJD\n-----END PRIVATE KEY-----";

        assertThatThrownBy(() -> new GithubAppJwtGenerator(propertiesWith(corrupted)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("QUJD");
    }

    /**
     * RSAPrivateCrtKey를 PKCS#1 RSAPrivateKey DER로 인코딩하는 테스트 전용 헬퍼.
     */
    private static final class Pkcs1Der {

        static byte[] encode(RSAPrivateCrtKey key) {
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            body.writeBytes(integer(java.math.BigInteger.ZERO));
            body.writeBytes(integer(key.getModulus()));
            body.writeBytes(integer(key.getPublicExponent()));
            body.writeBytes(integer(key.getPrivateExponent()));
            body.writeBytes(integer(key.getPrimeP()));
            body.writeBytes(integer(key.getPrimeQ()));
            body.writeBytes(integer(key.getPrimeExponentP()));
            body.writeBytes(integer(key.getPrimeExponentQ()));
            body.writeBytes(integer(key.getCrtCoefficient()));
            return tlv(0x30, body.toByteArray());
        }

        private static byte[] integer(java.math.BigInteger value) {
            return tlv(0x02, value.toByteArray());
        }

        private static byte[] tlv(int tag, byte[] value) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(tag);
            out.writeBytes(length(value.length));
            out.writeBytes(value);
            return out.toByteArray();
        }

        private static byte[] length(int length) {
            if (length < 0x80) {
                return new byte[]{(byte) length};
            }
            int byteCount = 0;
            for (int remaining = length; remaining > 0; remaining >>>= 8) {
                byteCount++;
            }
            byte[] encoded = new byte[byteCount + 1];
            encoded[0] = (byte) (0x80 | byteCount);
            for (int i = 0; i < byteCount; i++) {
                encoded[byteCount - i] = (byte) (length >>> (8 * i));
            }
            return encoded;
        }
    }
}
