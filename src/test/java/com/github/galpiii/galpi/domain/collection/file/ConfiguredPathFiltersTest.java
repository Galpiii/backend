package com.github.galpiii.galpi.domain.collection.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConfiguredPathFilters — 사용자 설정 glob")
class ConfiguredPathFiltersTest {

    @Nested
    @DisplayName("include")
    class Include {

        @Test
        @DisplayName("목록이 비어 있으면 전부 포함한다")
        void includesEverythingWhenNotConfigured() {
            ConfiguredPathFilters filters = ConfiguredPathFilters.of(List.of(), List.of());

            assertThat(filters.matchesInclude("docs/guide.md")).isTrue();
        }

        @Test
        @DisplayName("목록이 있으면 하나 이상에 맞아야 포함한다")
        void includesOnlyMatchingWhenConfigured() {
            ConfiguredPathFilters filters =
                    ConfiguredPathFilters.of(List.of("src/**"), List.of());

            assertThat(filters.matchesInclude("src/App.java")).isTrue();
            assertThat(filters.matchesInclude("docs/guide.md")).isFalse();
        }

        @Test
        @DisplayName("패턴이 전부 깨져 남은 것이 없어도 '설정하지 않음'으로 보지 않는다")
        void keepsConfiguredMeaningWhenEveryPatternIsInvalid() {
            ConfiguredPathFilters filters =
                    ConfiguredPathFilters.of(List.of("src/[", "docs/["), List.of());

            assertThat(filters.matchesInclude("src/App.java")).isFalse();
        }
    }

    @Nested
    @DisplayName("exclude")
    class Exclude {

        @Test
        @DisplayName("맞는 경로만 제외한다")
        void excludesMatchingPaths() {
            ConfiguredPathFilters filters =
                    ConfiguredPathFilters.of(List.of(), List.of("legacy/**"));

            assertThat(filters.matchesExclude("legacy/Old.java")).isTrue();
            assertThat(filters.matchesExclude("src/App.java")).isFalse();
        }

        @Test
        @DisplayName("깨진 패턴 하나가 나머지 패턴을 죽이지 않는다")
        void discardsOnlyTheInvalidPattern() {
            ConfiguredPathFilters filters =
                    ConfiguredPathFilters.of(List.of(), List.of("legacy/[", "build/**"));

            assertThat(filters.matchesExclude("build/out.js")).isTrue();
            assertThat(filters.matchesExclude("src/App.java")).isFalse();
        }

        @Test
        @DisplayName("null과 빈 문자열은 패턴으로 치지 않는다")
        void ignoresNullAndBlankPatterns() {
            ConfiguredPathFilters filters = ConfiguredPathFilters.of(
                    List.of(), Arrays.asList(null, "  ", "build/**"));

            assertThat(filters.matchesExclude("build/out.js")).isTrue();
            assertThat(filters.matchesExclude("src/App.java")).isFalse();
        }
    }
}
