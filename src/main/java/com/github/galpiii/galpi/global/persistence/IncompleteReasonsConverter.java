package com.github.galpiii.galpi.global.persistence;

import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;

import java.util.LinkedHashSet;
import java.util.List;

/** {@code incomplete_reasons} jsonb 컬럼 변환. 중복을 없애고 넣은 순서를 유지한다. */
@Converter
public class IncompleteReasonsConverter implements AttributeConverter<List<IncompleteReason>, String> {

    private static final TypeReference<List<IncompleteReason>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<IncompleteReason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return null;
        }
        return JsonCodec.write(List.copyOf(new LinkedHashSet<>(reasons)));
    }

    @Override
    public List<IncompleteReason> convertToEntityAttribute(String json) {
        return JsonCodec.read(json, TYPE, List.of());
    }
}
