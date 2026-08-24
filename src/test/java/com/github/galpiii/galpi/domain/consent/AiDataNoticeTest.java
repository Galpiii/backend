package com.github.galpiii.galpi.domain.consent;

import com.github.galpiii.galpi.domain.consent.config.ConsentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 버전과 문구가 따로 놀지 못하게 막는 테스트.
 *
 * <p>동의 이력은 "그때 무엇에 동의했는지"를 되짚을 수 있어야 근거가 된다. 버전만 남기면
 * 문구가 조용히 바뀌었을 때 같은 이름이 다른 내용을 가리키므로, 문구의 해시를 함께 남기고
 * 그 해시가 마이그레이션의 소급 값과 일치하는지 여기서 확인한다.
 */
@DisplayName("AiDataNotice — 버전과 문구는 한 몸이다")
class AiDataNoticeTest {

    /**
     * V11 마이그레이션이 기존 행에 채워 넣는 값.
     *
     * <p>문구를 고치면 이 값이 달라져 여기서 먼저 터진다. 그때 해야 할 일은 이 상수를 고치는
     * 것이 아니라 <b>새 버전을 추가하는 것</b>이다 — 기존 행이 가리키는 문구는 바뀌면 안 된다.
     */
    private static final String MIGRATED_HASH =
            "51f7fc300fab2b1b1dae6f64c357c1d53565ca2e01ce315873ff21df047690b7";

    @Test
    @DisplayName("2026-08-23 문구의 해시는 마이그레이션이 소급한 값과 같다")
    void hashMatchesMigration() {
        assertThat(AiDataNotice.hash("2026-08-23")).isEqualTo(MIGRATED_HASH);
    }

    @Test
    @DisplayName("현재 버전은 문구가 등록돼 있다")
    void currentVersionResolves() {
        assertThat(AiDataNotice.hasVersion(AiDataNotice.CURRENT_VERSION)).isTrue();
        assertThat(AiDataNotice.text(AiDataNotice.CURRENT_VERSION))
                .contains("외부 AI 서비스로 전송")
                // 완전한 보호를 약속하지 않는다는 한계 고지가 빠지면 안 된다.
                .contains("모든 민감정보 탐지를 보장하지는 않습니다");
    }

    @Test
    @DisplayName("모르는 버전의 문구는 만들어 내지 않는다")
    void refusesUnknownVersion() {
        assertThatThrownBy(() -> AiDataNotice.text("1999-01-01"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("문구가 없는 버전을 설정하면 기동 시점에 실패한다 — 읽은 적 없는 버전에 동의를 받을 수 없다")
    void configurationRejectsUnregisteredVersion() {
        assertThatThrownBy(() -> new ConsentProperties("1999-01-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1999-01-01");
    }

    @Test
    @DisplayName("설정을 비워 두면 코드가 들고 있는 현재 버전을 쓴다")
    void defaultsToCurrentVersion() {
        assertThat(new ConsentProperties(AiDataNotice.CURRENT_VERSION).aiDataVersion())
                .isEqualTo(AiDataNotice.CURRENT_VERSION);
    }
}
