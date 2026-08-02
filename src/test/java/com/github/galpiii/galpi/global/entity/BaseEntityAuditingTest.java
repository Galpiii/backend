package com.github.galpiii.galpi.global.entity;

import jakarta.persistence.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.data.mapping.context.PersistentEntities;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시각 컬럼이 {@code timestamptz}이므로 감사 필드도 {@link java.time.OffsetDateTime}이어야 한다.
 *
 * <p>Spring Data의 기본 {@code DateTimeProvider}는 {@code LocalDateTime}을 준다. 여기서 실제
 * 감사 핸들러를 돌려, 설정한 provider가 엔티티 필드 타입까지 제대로 채우는지 확인한다.
 * 이게 깨지면 저장 시점에야 드러나므로 단위 테스트로 고정해둔다.
 */
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
