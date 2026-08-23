package com.github.galpiii.galpi.global.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code analysis_runs.installation_snapshot} jsonb 변환.
 *
 * <p>{@code { "<githubRepositoryId>": <installationId> }} 형태다. JSON 객체의 키는 문자열이라
 * {@code Long} 키를 그대로 못 쓰고, 읽을 때 다시 숫자로 바꾼다.
 */
@Converter
public class InstallationSnapshotConverter implements AttributeConverter<Map<Long, Long>, String> {

    private static final TypeReference<Map<String, Long>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(Map<Long, Long> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            // NOT NULL 컬럼이라 빈 맵도 유효한 JSON으로 남겨야 한다.
            return "{}";
        }
        return JsonCodec.write(snapshot);
    }

    @Override
    public Map<Long, Long> convertToEntityAttribute(String json) {
        Map<String, Long> raw = JsonCodec.read(json, TYPE, Map.of());
        Map<Long, Long> snapshot = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (value != null && isNumeric(key)) {
                snapshot.put(Long.valueOf(key), value);
            }
        });
        return snapshot;
    }

    private static boolean isNumeric(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        try {
            Long.parseLong(key);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
