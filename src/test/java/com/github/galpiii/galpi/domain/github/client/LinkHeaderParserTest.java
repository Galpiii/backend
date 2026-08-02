package com.github.galpiii.galpi.domain.github.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LinkHeaderParser")
class LinkHeaderParserTest {

    private static final String FULL_HEADER = """
            <https://api.github.com/user/repos?page=2>; rel="next", \
            <https://api.github.com/user/repos?page=5>; rel="last", \
            <https://api.github.com/user/repos?page=1>; rel="first", \
            <https://api.github.com/user/repos?page=1>; rel="prev\"""";

    @Test
    @DisplayName("next 링크를 뽑는다")
    void extractsNext() {
        assertThat(LinkHeaderParser.next(FULL_HEADER))
                .contains("https://api.github.com/user/repos?page=2");
    }

    @Test
    @DisplayName("임의의 rel을 뽑는다")
    void extractsArbitraryRelation() {
        assertThat(LinkHeaderParser.relation(FULL_HEADER, "last"))
                .contains("https://api.github.com/user/repos?page=5");
    }

    @Test
    @DisplayName("마지막 페이지에는 next가 없다")
    void returnsEmptyOnLastPage() {
        String lastPage = "<https://api.github.com/user/repos?page=1>; rel=\"first\"";

        assertThat(LinkHeaderParser.next(lastPage)).isEmpty();
    }

    @Test
    @DisplayName("헤더가 없거나 비어 있으면 빈 값을 준다")
    void handlesMissingHeader() {
        assertThat(LinkHeaderParser.next(null)).isEmpty();
        assertThat(LinkHeaderParser.next("")).isEmpty();
        assertThat(LinkHeaderParser.next("   ")).isEmpty();
    }

    @Test
    @DisplayName("형식이 깨진 헤더에서도 예외를 내지 않는다")
    void toleratesMalformedHeader() {
        assertThat(LinkHeaderParser.next("garbage; rel=next")).isEmpty();
    }
}
