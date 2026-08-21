package com.github.galpiii.galpi.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class FeatureSpecPrompt {

    private static final String PROMPT_PATH = "ai/feature-spec-extraction-prompt.md";
    private static final String SCHEMA_PATH = "ai/feature-spec-extraction-schema.json";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private String instructions;
    private ResponseFormatTextJsonSchemaConfig schemaConfig;

    @PostConstruct
    void load() {
        instructions = readResource(PROMPT_PATH);
        schemaConfig = parseSchema(readResource(SCHEMA_PATH));
    }

    public String instructions() {
        return instructions;
    }

    public ResponseFormatTextJsonSchemaConfig schemaConfig() {
        return schemaConfig;
    }

    private String readResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("AI 리소스를 읽을 수 없습니다. path: " + path, e);
        }
    }

    private ResponseFormatTextJsonSchemaConfig parseSchema(String json) {
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
            throw new IllegalStateException("AI 응답 스키마가 올바르지 않습니다. path: " + SCHEMA_PATH, e);
        }
    }

    Map<String, JsonValue> schemaProperties() {
        return schemaConfig.schema()._additionalProperties();
    }
}
