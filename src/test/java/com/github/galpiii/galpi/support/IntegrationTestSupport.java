package com.github.galpiii.galpi.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 Postgres와 Redis를 붙인 통합 테스트의 바탕.
 *
 * <p>Flyway를 켜고 ddl-auto를 validate로 둔다. 이 조합이 아니면 마이그레이션과 엔티티 매핑이
 * 어긋나도 테스트가 통과해 버린다 — 컨테이너를 붙이는 가장 큰 이유가 이 대조다.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

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
