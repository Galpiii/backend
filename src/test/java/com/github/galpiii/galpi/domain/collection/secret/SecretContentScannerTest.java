package com.github.galpiii.galpi.domain.collection.secret;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecretContentScanner")
class SecretContentScannerTest {

    private static final String FAKE_GITHUB_TOKEN = "ghp_1234567890abcdefGHIJKLMNOPqrstuvwx12";
    /** AWS access key id는 접두사 4자 + 16자다. 길이가 어긋나면 진짜 키도 못 잡는다. */
    private static final String FAKE_AWS_KEY = "AKIA1234567890ABCDEF";
    private static final String FAKE_JWT =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r";

    private final SecretContentScanner scanner = new SecretContentScanner();

    @Nested
    @DisplayName("firstFinding()")
    class FirstFinding {

        @Test
        @DisplayName("GitHub 토큰을 탐지한다")
        void detectsGithubToken() {
            Optional<SecretFinding> finding =
                    scanner.firstFinding("const client = new Octokit({ auth: '" + FAKE_GITHUB_TOKEN + "' });");

            assertThat(finding).isPresent();
            assertThat(finding.get().kind()).isEqualTo(SecretPatternKind.GITHUB_TOKEN);
        }

        @ParameterizedTest(name = "{0} 접두사를 탐지한다")
        @ValueSource(strings = {"ghp_", "gho_", "ghu_", "ghs_", "ghr_"})
        @DisplayName("GitHub 토큰 접두사 전부를 탐지한다")
        void detectsEveryGithubTokenPrefix(String prefix) {
            String token = prefix + "1234567890abcdefGHIJKLMNOPqrstuvwx12";

            assertThat(scanner.firstFinding("auth=" + token))
                    .get()
                    .extracting(SecretFinding::kind)
                    .isEqualTo(SecretPatternKind.GITHUB_TOKEN);
        }

        @Test
        @DisplayName("github_pat_ 형태의 fine-grained 토큰을 탐지한다")
        void detectsFineGrainedToken() {
            assertThat(scanner.firstFinding("token: github_pat_11ABCDEFG0abcdefghijklmnop"))
                    .get()
                    .extracting(SecretFinding::kind)
                    .isEqualTo(SecretPatternKind.GITHUB_TOKEN);
        }

        @Test
        @DisplayName("AWS access key를 탐지한다")
        void detectsAwsKey() {
            assertThat(scanner.firstFinding("aws_access_key_id = " + FAKE_AWS_KEY))
                    .get()
                    .extracting(SecretFinding::kind)
                    .isEqualTo(SecretPatternKind.AWS_ACCESS_KEY);
        }

        @Test
        @DisplayName("PEM 개인키 블록을 탐지한다")
        void detectsPrivateKeyBlock() {
            String content = """
                    -----BEGIN RSA PRIVATE KEY-----
                    MIIEowIBAAKCAQEA
                    -----END RSA PRIVATE KEY-----
                    """;

            assertThat(scanner.firstFinding(content))
                    .get()
                    .extracting(SecretFinding::kind)
                    .isEqualTo(SecretPatternKind.PRIVATE_KEY_BLOCK);
        }

        @Test
        @DisplayName("JWT를 탐지한다")
        void detectsJwt() {
            assertThat(scanner.firstFinding("Authorization: Bearer " + FAKE_JWT))
                    .get()
                    .extracting(SecretFinding::kind)
                    .isEqualTo(SecretPatternKind.JWT);
        }

        @Test
        @DisplayName("긴 리터럴을 대입한 자격증명 형태를 탐지한다")
        void detectsAssignedCredential() {
            assertThat(scanner.firstFinding("api_key = \"a1b2c3d4e5f6g7h8i9j0k1l2\""))
                    .get()
                    .extracting(SecretFinding::kind)
                    .isEqualTo(SecretPatternKind.ASSIGNED_CREDENTIAL);
        }

        @Test
        @DisplayName("탐지된 줄 번호를 함께 돌려준다")
        void reportsLineNumber() {
            String content = "line one\nline two\nsecret=" + FAKE_GITHUB_TOKEN + "\nline four";

            assertThat(scanner.firstFinding(content)).get().extracting(SecretFinding::line).isEqualTo(3);
        }

        @ParameterizedTest(name = "{0} 는 탐지하지 않는다")
        @ValueSource(strings = {
                "password: ${DB_PASSWORD}",
                "String password = passwordEncoder.encode(rawPassword);",
                "private final String token = tokenRepository.findByUserId(userId);",
                "api_key: your_api_key_here",
                "secret: changeme-please-update",
                "spring.datasource.password=galpi",
                "public String getAccessToken() { return accessToken; }"
        })
        @DisplayName("자격증명이 아닌 흔한 코드·설정을 오탐하지 않는다")
        void doesNotFlagOrdinaryCode(String line) {
            assertThat(scanner.firstFinding(line)).isEmpty();
        }

        @Test
        @DisplayName("커밋 SHA 40자를 고엔트로피로 오탐하지 않는다")
        void doesNotFlagCommitSha() {
            // hex 16종뿐이라 엔트로피 상한이 4.0이다. 임계값 4.5가 이걸 넘기지 않는 근거다.
            assertThat(scanner.firstFinding("base: 9f8e7d6c5b4a39281706f5e4d3c2b1a098765432")).isEmpty();
        }

        @Test
        @DisplayName("평범한 소스 코드에는 탐지가 없다")
        void findsNothingInPlainSource() {
            String source = """
                    package com.example.app;

                    public class OrderService {
                        public Order place(Long userId, List<Item> items) {
                            return orderRepository.save(Order.of(userId, items));
                        }
                    }
                    """;

            assertThat(scanner.firstFinding(source)).isEmpty();
        }

        @Test
        @DisplayName("빈 값과 null은 탐지가 없다")
        void handlesEmptyInput() {
            assertThat(scanner.firstFinding((String) null)).isEmpty();
            assertThat(scanner.firstFinding("")).isEmpty();
        }

        @Test
        @DisplayName("파일에서도 같은 규칙으로 탐지한다")
        void scansFile(@TempDir Path directory) throws IOException {
            Path file = directory.resolve("config.js");
            Files.writeString(file, "module.exports = { token: '" + FAKE_GITHUB_TOKEN + "' };");

            assertThat(scanner.firstFinding(file))
                    .get()
                    .extracting(SecretFinding::kind)
                    .isEqualTo(SecretPatternKind.GITHUB_TOKEN);
        }

        @Test
        @DisplayName("UTF-8이 아닌 바이트가 섞여도 예외 없이 스캔한다")
        void scansFileWithInvalidUtf8(@TempDir Path directory) throws IOException {
            Path file = directory.resolve("mixed.txt");
            byte[] broken = {(byte) 0xC3, (byte) 0x28};
            byte[] token = ("\ntoken=" + FAKE_GITHUB_TOKEN).getBytes(StandardCharsets.UTF_8);
            byte[] content = new byte[broken.length + token.length];
            System.arraycopy(broken, 0, content, 0, broken.length);
            System.arraycopy(token, 0, content, broken.length, token.length);
            Files.write(file, content);

            assertThat(scanner.firstFinding(file))
                    .get()
                    .extracting(SecretFinding::kind)
                    .isEqualTo(SecretPatternKind.GITHUB_TOKEN);
        }
    }

    @Nested
    @DisplayName("mask()")
    class Mask {

        @Test
        @DisplayName("PR 본문의 GitHub 토큰을 지우고 나머지 설명은 남긴다")
        void masksTokenAndKeepsBody() {
            String body = "로그인 기능을 구현했습니다.\n테스트용 토큰: " + FAKE_GITHUB_TOKEN + "\n리뷰 부탁드립니다.";

            SecretContentScanner.MaskedText masked = scanner.mask(body);

            assertThat(masked.text())
                    .doesNotContain(FAKE_GITHUB_TOKEN)
                    .contains("***")
                    .contains("로그인 기능을 구현했습니다.")
                    .contains("리뷰 부탁드립니다.");
            assertThat(masked.masked()).isTrue();
            assertThat(masked.findings())
                    .extracting(SecretFinding::kind)
                    .containsExactly(SecretPatternKind.GITHUB_TOKEN);
        }

        @Test
        @DisplayName("커밋 메시지의 AWS 키를 지운다")
        void masksAwsKeyInCommitMessage() {
            SecretContentScanner.MaskedText masked =
                    scanner.mask("fix: 배포 스크립트에서 " + FAKE_AWS_KEY + " 제거");

            assertThat(masked.text()).doesNotContain(FAKE_AWS_KEY).contains("***");
        }

        @Test
        @DisplayName("여러 줄에 걸친 PEM 블록을 통째로 지운다")
        void masksMultiLinePemBlock() {
            String body = """
                    설정 예시입니다.
                    -----BEGIN RSA PRIVATE KEY-----
                    MIIEowIBAAKCAQEAsecretkeymaterialhere
                    -----END RSA PRIVATE KEY-----
                    끝.""";

            SecretContentScanner.MaskedText masked = scanner.mask(body);

            assertThat(masked.text())
                    .doesNotContain("MIIEowIBAAKCAQEAsecretkeymaterialhere")
                    .contains("설정 예시입니다.")
                    .contains("끝.");
        }

        @Test
        @DisplayName("대입 형태는 키 이름을 남기고 값만 지운다")
        void masksOnlyAssignedValue() {
            SecretContentScanner.MaskedText masked = scanner.mask("api_key = \"a1b2c3d4e5f6g7h8i9j0k1l2\"");

            assertThat(masked.text())
                    .contains("api_key")
                    .doesNotContain("a1b2c3d4e5f6g7h8i9j0k1l2");
        }

        @Test
        @DisplayName("한 텍스트에 여러 종류가 있으면 모두 지운다")
        void masksEveryKind() {
            String body = FAKE_GITHUB_TOKEN + " 와 " + FAKE_AWS_KEY + " 와 " + FAKE_JWT;

            SecretContentScanner.MaskedText masked = scanner.mask(body);

            assertThat(masked.text())
                    .doesNotContain(FAKE_GITHUB_TOKEN)
                    .doesNotContain(FAKE_AWS_KEY)
                    .doesNotContain(FAKE_JWT);
            assertThat(masked.findings()).extracting(SecretFinding::kind)
                    .contains(SecretPatternKind.GITHUB_TOKEN,
                            SecretPatternKind.AWS_ACCESS_KEY,
                            SecretPatternKind.JWT);
        }

        @Test
        @DisplayName("탐지가 없으면 원문을 그대로 돌려준다")
        void returnsInputWhenNothingDetected() {
            String body = "회원 가입 폼 검증 로직을 추가했습니다.";

            SecretContentScanner.MaskedText masked = scanner.mask(body);

            assertThat(masked.text()).isEqualTo(body);
            assertThat(masked.masked()).isFalse();
        }

        @Test
        @DisplayName("정규식 치환 문자가 원문에 있어도 그대로 보존한다")
        void preservesRegexReplacementCharacters() {
            String body = "비용은 $1 이고 경로는 C:\\temp 입니다. 토큰: " + FAKE_GITHUB_TOKEN;

            SecretContentScanner.MaskedText masked = scanner.mask(body);

            assertThat(masked.text())
                    .contains("$1")
                    .contains("C:\\temp")
                    .doesNotContain(FAKE_GITHUB_TOKEN);
        }

        @Test
        @DisplayName("로그용 요약에는 종류만 담기고 값은 담기지 않는다")
        void logSummaryHasKindsOnly() {
            SecretContentScanner.MaskedText masked = scanner.mask("token=" + FAKE_GITHUB_TOKEN);

            assertThat(masked.kindsForLog())
                    .isEqualTo(SecretPatternKind.GITHUB_TOKEN.name())
                    .doesNotContain(FAKE_GITHUB_TOKEN);
        }

        @Test
        @DisplayName("null과 빈 문자열을 그대로 통과시킨다")
        void handlesNullAndEmpty() {
            assertThat(scanner.mask(null).text()).isNull();
            assertThat(scanner.mask("").text()).isEmpty();
        }
    }
}
