package com.github.galpiii.galpi.global.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;

import java.util.List;

/** {@code analysis_configs.include_paths} / {@code exclude_paths} jsonb 변환. */
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return JsonCodec.write(values);
    }

    @Override
    public List<String> convertToEntityAttribute(String json) {
        return JsonCodec.read(json, TYPE, List.of());
    }
}
