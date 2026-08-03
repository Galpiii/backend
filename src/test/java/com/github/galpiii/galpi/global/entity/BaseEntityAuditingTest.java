package com.github.galpiii.galpi.global.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.data.mapping.context.PersistentEntities;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BaseEntity — timestamptz 감사 필드")
class BaseEntityAuditingTest {

    /**
     * @Entity를 붙이지 않는다. AuditingHandler는 매핑된 엔티티가 아니어도 애노테이션만 보고
     * 동작하는데, 붙이면 엔티티 스캔에 걸려 모든 애플리케이션 컨텍스트가 존재하지 않는 테이블을
     * 기대하게 된다.
     */
    static class Sample extends BaseEntity {
    }

    @Test
    @DisplayName("생성·수정 시각이 OffsetDateTime으로 채워진다")
    void populatesOffsetDateTime() {
        AuditingHandler handler = new AuditingHandler(PersistentEntities.of());
        handler.setDateTimeProvider(JpaAuditingConfig.offsetDateTimeProvider());

        Sample created = handler.markCreated(new Sample());
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();

        Sample modified = handler.markModified(created);
        assertThat(modified.getUpdatedAt()).isNotNull();
    }
}
