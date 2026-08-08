package com.github.galpiii.galpi.global.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TokenMasker")
class TokenMaskerTest {

    @Nested
    @DisplayName("mask()")
    class Mask {

        @ParameterizedTest(name = "{0} 는 마스킹된다")
        @ValueSource(strings = {
                "ghp_1234567890abcdefghijklmnopqrstuvwx",
                "gho_1234567890abcdefghijklmnopqrstuvwx",
                "ghu_1234567890abcdefghijklmnopqrstuvwx",
                "ghs_1234567890abcdefghijklmnopqrstuvwx",
                "ghr_1234567890abcdefghijklmnopqrstuvwx",
                "github_pat_11ABCDEFG0abcdefghijklmnop"
        })
        @DisplayName("GitHub 토큰 계열을 모두 가린다")
        void masksGithubTokens(String token) {
            String masked = TokenMasker.mask("token=" + token + " 사용");

            assertThat(masked).doesNotContain(token);
            assertThat(masked).contains("***");
        }

        @Test
        @DisplayName("JWT 형태 문자열을 가린다")
        void masksJwt() {
            String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.abcdefghijk";

            assertThat(TokenMasker.mask("Bearer " + jwt)).doesNotContain(jwt);
        }

        @Test
        @DisplayName("Authorization 헤더 값을 가린다")
        void masksAuthorizationHeader() {
            String masked = TokenMasker.mask("Authorization: Bearer supersecretvalue");

            assertThat(masked).doesNotContain("supersecretvalue");
        }

        @Test
        @DisplayName("URL 쿼리스트링의 민감 파라미터를 가린다")
        void masksSensitiveQueryParams() {
            String url = "https://github.com/login/oauth/access_token"
                    + "?client_secret=abcd1234&code=xyz789&state=st4te";

            String masked = TokenMasker.mask(url);

            assertThat(masked)
                    .doesNotContain("abcd1234")
                    .doesNotContain("xyz789")
                    .doesNotContain("st4te");
        }

        @Test
        @DisplayName("PEM 개인키 블록을 통째로 가린다")
        void masksPemBlock() {
            String pem = """
                    -----BEGIN RSA PRIVATE KEY-----
                    MIIEowIBAAKCAQEAsecretkeymaterial
                    -----END RSA PRIVATE KEY-----""";

            assertThat(TokenMasker.mask("key=" + pem)).doesNotContain("MIIEowIBAAKCAQEAsecretkeymaterial");
        }

        @Test
        @DisplayName("일반 문자열은 건드리지 않는다")
        void leavesPlainTextIntact() {
            assertThat(TokenMasker.mask("GET /user -> 200 (13ms)")).isEqualTo("GET /user -> 200 (13ms)");
        }

        @Test
        @DisplayName("null과 빈 문자열을 그대로 통과시킨다")
        void handlesNullAndEmpty() {
            assertThat(TokenMasker.mask(null)).isNull();
            assertThat(TokenMasker.mask("")).isEmpty();
        }
    }

}
