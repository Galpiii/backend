package com.github.galpiii.galpi.domain.github.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GithubInstallationTokenCache — 범위 해시")
class GithubInstallationTokenCacheTest {

    @Test
    @DisplayName("저장소 id 순서가 달라도 같은 키가 된다")
    void producesSameHashRegardlessOfOrder() {
        String ascending = GithubInstallationTokenCache.scopeHash(List.of(1L, 2L, 3L));
        String descending = GithubInstallationTokenCache.scopeHash(List.of(3L, 2L, 1L));
        String shuffled = GithubInstallationTokenCache.scopeHash(List.of(2L, 1L, 3L));

        assertThat(ascending).isEqualTo(descending).isEqualTo(shuffled);
    }

    @Test
    @DisplayName("중복은 하나로 본다")
    void ignoresDuplicates() {
        assertThat(GithubInstallationTokenCache.scopeHash(List.of(1L, 1L, 2L)))
                .isEqualTo(GithubInstallationTokenCache.scopeHash(List.of(1L, 2L)));
    }

    @Test
    @DisplayName("범위가 다르면 다른 키가 된다")
    void differentScopeProducesDifferentHash() {
        // 토큰의 유효 범위가 곧 캐시 키의 의미다. 범위가 다른데 같은 키를 쓰면 필요 이상으로
        // 넓은 토큰을 재사용하게 된다.
        assertThat(GithubInstallationTokenCache.scopeHash(List.of(1L, 2L)))
                .isNotEqualTo(GithubInstallationTokenCache.scopeHash(List.of(1L, 2L, 3L)));
    }

    @Test
    @DisplayName("숫자를 이어 붙인 값이 우연히 겹치지 않는다")
    void doesNotCollideOnConcatenation() {
        // 구분자가 없으면 [1, 23]과 [12, 3]이 모두 "123"이 되어 같은 키가 된다.
        assertThat(GithubInstallationTokenCache.scopeHash(List.of(1L, 23L)))
                .isNotEqualTo(GithubInstallationTokenCache.scopeHash(List.of(12L, 3L)));
    }

    @Test
    @DisplayName("null이 섞여도 무시하고 해시한다")
    void ignoresNullIds() {
        assertThat(GithubInstallationTokenCache.scopeHash(java.util.Arrays.asList(1L, null, 2L)))
                .isEqualTo(GithubInstallationTokenCache.scopeHash(List.of(1L, 2L)));
    }

    @Test
    @DisplayName("정규화한 범위는 정렬되고 중복이 없다")
    void normalizesScope() {
        assertThat(GithubInstallationTokenCache.normalizeScope(List.of(3L, 1L, 3L, 2L)))
                .containsExactly(1L, 2L, 3L);
    }
}
