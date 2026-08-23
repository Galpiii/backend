package com.github.galpiii.galpi.global.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * jsonb 컬럼용 최소 JSON 코덱.
 *
 * <p>JPA {@code AttributeConverter}는 Hibernate가 직접 만들기 때문에 스프링 빈을 주입받지
 * 못한다. 컨테이너의 {@code ObjectMapper}를 정적으로 붙잡아 두는 방법도 있지만, 부팅 순서에
 * 의존하는 정적 상태를 만드는 값이 여기엔 없다 — 다루는 값이 enum 이름 목록과 숫자 맵뿐이라
 * 애플리케이션 전역 직렬화 설정과 무관하다.
 */
public final class JsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonCodec() {
    }

    /** 비어 있으면 {@code null}을 돌려준다. 컬럼에 {@code []}가 아니라 NULL이 들어가게 하려는 것이다. */
    public static String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "jsonb 컬럼 직렬화에 실패했습니다: " + e.getClass().getSimpleName());
        }
    }

    public static <T> T read(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException e) {
            // 값이 깨졌다고 해서 행 전체를 못 읽게 만들 이유는 없다. 이 컬럼들은 전부 부가 정보다.
            return fallback;
        }
    }
}
