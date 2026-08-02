package com.github.galpiii.galpi.global.entity;

import jakarta.persistence.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.data.mapping.context.PersistentEntities;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BaseEntity — timestamptz 감사 필드")
class BaseEntityAuditingTest {

    @Entity
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
