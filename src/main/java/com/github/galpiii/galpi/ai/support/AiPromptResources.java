package com.github.galpiii.galpi.ai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 클래스패스의 프롬프트·응답 스키마를 읽는다.
 *
 * <p>프롬프트를 리소스 파일로 두는 이유는 문구가 코드가 아니기 때문이다. 자바 문자열에 넣으면
 * 줄바꿈과 따옴표 이스케이프에 묻혀 읽기 어렵고, 문구를 다듬는 변경이 코드 변경으로 보인다.
 *
 * <p>읽기에 실패하면 기동을 실패시킨다. 프롬프트나 스키마가 없는 채로 뜨면 첫 호출에서야
 * 드러나고, 그때는 이미 사용자가 기다리는 중이다.
 */
public final class AiPromptResources {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AiPromptResources() {
    }

    public static String readText(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("AI 리소스를 읽을 수 없습니다. path: " + path, e);
        }
    }

    /**
     * 스키마 파일을 Responses API의 {@code text.format} 설정으로 바꾼다.
     *
     * <p>파일은 {@code name} / {@code strict} / {@code schema} 세 키를 가진다. SDK의 스키마
     * 빌더가 임의 JSON을 그대로 받지 못해 {@code schema}의 필드를 하나씩 옮겨 담는다.
     */
    public static ResponseFormatTextJsonSchemaConfig readSchema(String path) {
        String json = readText(path);
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            JsonNode schema = root.required("schema");

            ResponseFormatTextJsonSchemaConfig.Schema.Builder schemaBuilder =
                    ResponseFormatTextJsonSchemaConfig.Schema.builder();
            schema.properties().forEach(field ->
                    schemaBuilder.putAdditionalProperty(
                            field.getKey(), JsonValue.fromJsonNode(field.getValue())));

            return ResponseFormatTextJsonSchemaConfig.builder()
                    .name(root.required("name").asText())
                    .strict(root.required("strict").asBoolean())
                    .schema(schemaBuilder.build())
                    .build();
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("AI 응답 스키마가 올바르지 않습니다. path: " + path, e);
        }
    }
}
