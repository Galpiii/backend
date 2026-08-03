package com.github.galpiii.galpi.global.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TokenCipher — AES-256-GCM 토큰 암복호화")
class TokenCipherTest {

    private static final String TOKEN = "ghu_abcdefghijklmnopqrstuvwxyz0123456789";

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static TokenCipher cipher(int currentVersion, Map<Integer, String> keys) {
        return new TokenCipher(new TokenEncryptionProperties(currentVersion, keys));
    }

    /**
     * 설정에 다음 버전 슬롯을 미리 비워 두고 열어야, 키를 돌릴 때 설정 파일을 고쳐 재배포하지 않고
     * 환경변수만 채워서 올릴 수 있다.
     */
    @Test
    @DisplayName("비어 있는 키 슬롯은 아직 쓰지 않는 버전으로 보고 건너뛴다")
    void ignoresBlankKeySlots() {
        String key = randomKey();
        TokenCipher cipher = cipher(1, Map.of(1, key, 2, "", 3, "   "));

        assertThat(cipher.decrypt(cipher.encrypt(TOKEN), 1)).isEqualTo(TOKEN);
        assertThatThrownBy(() -> cipher.decrypt("irrelevant", 2))
                .isInstanceOf(TokenCipherException.class);
    }

    @Test
    @DisplayName("현재 버전 슬롯이 비어 있으면 기동에 실패한다")
    void failsWhenCurrentVersionSlotIsBlank() {
        assertThatThrownBy(() -> cipher(2, Map.of(1, randomKey(), 2, "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current-version=2");
    }

    @Test
    @DisplayName("암호화한 값을 그대로 복호화한다")
    void roundTrips() {
        TokenCipher cipher = cipher(1, Map.of(1, randomKey()));

        String encrypted = cipher.encrypt(TOKEN);

        assertThat(cipher.decrypt(encrypted, 1)).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("암호문에 평문이 남지 않는다")
    void hidesPlaintext() {
        TokenCipher cipher = cipher(1, Map.of(1, randomKey()));

        assertThat(cipher.encrypt(TOKEN)).doesNotContain(TOKEN).doesNotContain("ghu_");
    }

    @Test
    @DisplayName("같은 평문도 매번 다른 암호문이 된다 (IV 재사용 없음)")
    void usesFreshIvEveryTime() {
        TokenCipher cipher = cipher(1, Map.of(1, randomKey()));

        assertThat(cipher.encrypt(TOKEN)).isNotEqualTo(cipher.encrypt(TOKEN));
    }

    @Test
    @DisplayName("암호문이 조작되면 인증 태그 검증에서 걸러진다")
    void rejectsTamperedCiphertext() {
        TokenCipher cipher = cipher(1, Map.of(1, randomKey()));
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt(TOKEN));
        raw[raw.length - 1] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(raw), 1))
                .isInstanceOf(TokenCipherException.class);
    }

    @Test
    @DisplayName("다른 키로는 복호화되지 않는다")
    void rejectsWrongKey() {
        String encrypted = cipher(1, Map.of(1, randomKey())).encrypt(TOKEN);
        TokenCipher other = cipher(1, Map.of(1, randomKey()));

        assertThatThrownBy(() -> other.decrypt(encrypted, 1))
                .isInstanceOf(TokenCipherException.class);
    }

    @Test
    @DisplayName("키를 회전해도 과거 버전으로 암호화된 값은 계속 복호화된다")
    void supportsKeyRotation() {
        String v1 = randomKey();
        String legacy = cipher(1, Map.of(1, v1)).encrypt(TOKEN);

        TokenCipher rotated = cipher(2, Map.of(1, v1, 2, randomKey()));

        assertThat(rotated.currentVersion()).isEqualTo(2);
        assertThat(rotated.decrypt(legacy, 1)).isEqualTo(TOKEN);
        assertThat(rotated.decrypt(rotated.encrypt(TOKEN), 2)).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("설정에 없는 키 버전으로는 복호화하지 않는다")
    void rejectsUnknownVersion() {
        TokenCipher cipher = cipher(1, Map.of(1, randomKey()));
        String encrypted = cipher.encrypt(TOKEN);

        assertThatThrownBy(() -> cipher.decrypt(encrypted, 9))
                .isInstanceOf(TokenCipherException.class);
    }

    @Test
    @DisplayName("현재 버전 키가 설정에 없으면 기동을 막는다")
    void failsFastWhenCurrentKeyMissing() {
        assertThatThrownBy(() -> cipher(2, Map.of(1, randomKey())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("32바이트가 아닌 키는 기동을 막는다")
    void failsFastOnWrongKeyLength() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> cipher(1, Map.of(1, shortKey)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트");
    }

    @Test
    @DisplayName("예외 메시지에 평문이나 키가 섞이지 않는다")
    void neverLeaksSecretsInMessages() {
        TokenCipher cipher = cipher(1, Map.of(1, randomKey()));

        assertThatThrownBy(() -> cipher.decrypt("not-base64!!", 1))
                .isInstanceOf(TokenCipherException.class)
                .hasMessageNotContaining(TOKEN);
    }
}
