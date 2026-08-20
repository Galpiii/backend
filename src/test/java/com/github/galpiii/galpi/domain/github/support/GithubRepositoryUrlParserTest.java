package com.github.galpiii.galpi.domain.github.support;

import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.support.GithubRepositoryUrlParser.RepositoryUrl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("저장소 URL 파싱")
class GithubRepositoryUrlParserTest {

    private final GithubRepositoryUrlParser parser =
            new GithubRepositoryUrlParser(properties());

    private static GithubAppProperties properties() {
        return new GithubAppProperties(
                "12345", "galpi-app", "Iv1.client", "secret", "pem", "https://api.galpi.dev",
                "2022-11-28", "https://api.github.com", "https://github.com", "Galpi",
                List.of("https://galpi.dev"), "https://galpi.dev/auth/callback",
                Duration.ofSeconds(5), Duration.ofSeconds(15), 2, 10);
    }

    @Nested
    @DisplayName("정규화")
    class Normalization {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://github.com/galpiii/backend",
                "https://github.com/galpiii/backend/",
                "https://github.com/galpiii/backend.git",
                "https://github.com/galpiii/backend.GIT",
                "https://github.com/galpiii/backend/tree/main",
                "https://github.com/galpiii/backend/tree/feature/#18",
                "https://github.com/galpiii/backend/blob/main/README.md",
                "https://github.com/galpiii/backend/pull/18",
                "https://github.com/galpiii/backend?tab=readme-ov-file",
                "https://www.github.com/galpiii/backend",
                "http://github.com/galpiii/backend",
                "github.com/galpiii/backend"
        })
        @DisplayName("붙여 넣은 모양이 달라도 같은 저장소로 읽는다")
        void normalizesUserPastedForms(String url) {
            assertThat(parser.parse(url))
                    .map(RepositoryUrl::fullName)
                    .contains("galpiii/backend");
        }

        @Test
        @DisplayName("대소문자는 원문 그대로 둔다. 대조는 대소문자를 무시한다")
        void keepsOriginalCase() {
            assertThat(parser.parse("https://github.com/Galpiii/Backend"))
                    .map(RepositoryUrl::fullName)
                    .contains("Galpiii/Backend");
        }
    }

    @Nested
    @DisplayName("거부")
    class Rejection {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://gitlab.com/galpiii/backend",
                "https://github.com.evil.example/galpiii/backend",
                "https://evil.example/github.com/galpiii/backend",
                "https://github.com/galpiii",
                "https://github.com/",
                "git@github.com:galpiii/backend.git",
                "ftp://github.com/galpiii/backend",
                "https://user:pass@github.com/galpiii/backend",
                "그냥 문자열",
                " "
        })
        @DisplayName("GitHub 저장소 URL이 아니면 받지 않는다")
        void rejectsNonRepositoryUrls(String url) {
            assertThat(parser.parse(url)).isEmpty();
        }

        @Test
        @DisplayName("null도 조용히 빈 값이다")
        void rejectsNull() {
            assertThat(parser.parse(null)).isEmpty();
        }

        @Test
        @DisplayName("소유자 이름 규칙을 벗어나면 받지 않는다")
        void rejectsInvalidOwner() {
            assertThat(parser.parse("https://github.com/-galpi/backend")).isEmpty();
            assertThat(parser.parse("https://github.com/gal_pi/backend")).isEmpty();
        }

        @Test
        @DisplayName("경로 조각이 저장소 이름이 아닌 경우를 걸러낸다")
        void rejectsDotNames() {
            assertThat(parser.parse("https://github.com/galpiii/..")).isEmpty();
        }
    }
}
