package com.github.galpiii.galpi.support;

import com.openai.client.OpenAIClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 실제 Postgres와 Redis를 붙인 통합 테스트의 바탕.
 *
 * <p>Flyway를 켜고 ddl-auto를 validate로 둔다. 이 조합이 아니면 마이그레이션과 엔티티 매핑이
 * 어긋나도 테스트가 통과해 버린다 — 컨테이너를 붙이는 가장 큰 이유가 이 대조다.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 실제 OpenAI 호출을 원천 차단한다.
     *
     * <p>테스트용 키로도 요청은 그대로 나가 매 빌드마다 인증 실패가 쌓인다. 호출하는 쪽을
     * 대체하는 것은 테스트마다 잊을 수 있으므로 클라이언트 자체를 여기서 막는다.
     */
    @MockitoBean
    private OpenAIClient openAIClient;

    /**
     * 테스트마다 DB를 비운다.
     *
     * <p>클래스가 각자 아는 테이블만 지우면 자식 테이블이 하나 늘 때마다 지우는 쪽이 그것을
     * 모른 채 FK 위반으로 터진다. 어떤 테이블이 있는지 DB에 직접 묻고 CASCADE로 지워 삭제
     * 순서를 아무도 관리하지 않게 한다.
     */
    @BeforeEach
    void cleanDatabase() {
        String tables = jdbcTemplate.queryForObject("""
                SELECT string_agg(quote_ident(tablename), ', ')
                FROM pg_tables
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                """, String.class);

        jdbcTemplate.execute("TRUNCATE TABLE " + tables + " CASCADE");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("galpi.github.private-key", TestRsaKeys::generatePrivateKeyPem);

        registry.add("spring.datasource.url", Containers::jdbcUrl);
        registry.add("spring.datasource.username", Containers::username);
        registry.add("spring.datasource.password", Containers::password);
        registry.add("spring.data.redis.host", Containers::redisHost);
        registry.add("spring.data.redis.port", Containers::redisPort);

        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access", () -> true);
    }
}
