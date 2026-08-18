package com.github.galpiii.galpi.domain.auth.support;

import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RedirectUriValidator — 오픈 리다이렉트 방지")
class RedirectUriValidatorTest {

    private final RedirectUriValidator validator = new RedirectUriValidator(new GithubAppProperties(
            "12345", "galpi-app", "Iv1.client", "secret", "pem", "https://api.galpi.dev",
            "2022-11-28", "https://api.github.com", "https://github.com", "Galpi",
            List.of("https://galpi.dev", "http://localhost:3000"),
            "https://galpi.dev/auth/callback",
            Duration.ofSeconds(5), Duration.ofSeconds(15), 2, 10));

    @Test
    @DisplayName("사이트 내부 상대경로는 허용한다")
    void allowsRelativePath() {
        assertThat(validator.validate("/projects/3")).isEqualTo("/projects/3");
    }

    @Test
    @DisplayName("화이트리스트에 있는 origin은 허용한다")
    void allowsWhitelistedOrigin() {
        assertThat(validator.validate("https://galpi.dev/projects")).isEqualTo("https://galpi.dev/projects");
    }

    @ParameterizedTest(name = "{0} 은 거부된다")
    @ValueSource(strings = {
            "https://evil.example.com/steal",
            "//evil.example.com",
            "http://galpi.dev.evil.com",
            "javascript:alert(1)",
            "https://galpi.dev:8443/x",
            "https://galpi.dev@evil.example.com/"
    })
    @DisplayName("허용되지 않은 대상은 거부한다")
    void rejectsUntrustedTargets(String target) {
        assertThatThrownBy(() -> validator.validate(target))
                .isInstanceOf(BadRequestException.class)
                .extracting(e -> ((GlobalException) e).getErrorCode())
                .isEqualTo(ErrorCode.GITHUB_REDIRECT_NOT_ALLOWED);
    }

    @ParameterizedTest(name = "{0} 은 거부된다")
    @ValueSource(strings = {
            "/\\evil.example.com",
            "/\\/evil.example.com",
            "\\/evil.example.com",
            "/projects\\..\\evil",
            "/\tevil",
            "/pro\njects"
    })
    @DisplayName("백슬래시·제어문자로 //를 우회하려는 대상은 거부한다")
    void rejectsBackslashBypass(String target) {
        assertThatThrownBy(() -> validator.validate(target))
                .isInstanceOf(BadRequestException.class)
                .extracting(e -> ((GlobalException) e).getErrorCode())
                .isEqualTo(ErrorCode.GITHUB_REDIRECT_NOT_ALLOWED);
    }

    @Test
    @DisplayName("복귀 대상이 없으면 빈 문자열을 준다")
    void allowsEmptyTarget() {
        assertThat(validator.validate(null)).isEmpty();
        assertThat(validator.validate("  ")).isEmpty();
    }

    @Test
    @DisplayName("프론트 콜백 URL에 로그인 코드를 인코딩해 붙인다")
    void buildsFrontendCallback() {
        String url = validator.buildFrontendCallback("code+with/special=chars", "/projects/3");

        assertThat(url)
                .startsWith("https://galpi.dev/auth/callback?code=")
                .contains("code%2Bwith%2Fspecial%3Dchars")
                .contains("returnTo=%2Fprojects%2F3");
    }

    @Test
    @DisplayName("복귀 대상이 없으면 returnTo를 붙이지 않는다")
    void omitsReturnToWhenAbsent() {
        assertThat(validator.buildFrontendCallback("abc", "")).doesNotContain("returnTo");
    }

    @Test
    @DisplayName("오류 리다이렉트에 상세 사유를 담지 않는다")
    void buildsErrorRedirect() {
        String url = validator.buildFrontendError(ErrorCode.GITHUB_OAUTH_FAILED.getCode(), "");

        assertThat(url).isEqualTo("https://galpi.dev/auth/callback?error=GITHUB-004");
    }

    @Test
    @DisplayName("오류 리다이렉트도 복귀 대상을 인코딩해 붙인다")
    void buildsErrorRedirectWithReturnTo() {
        String url = validator.buildFrontendError(ErrorCode.GITHUB_OAUTH_FAILED.getCode(), "/projects/3");

        assertThat(url).isEqualTo("https://galpi.dev/auth/callback?error=GITHUB-004&returnTo=%2Fprojects%2F3");
    }
}
