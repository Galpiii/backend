package com.github.galpiii.galpi.ai;

import com.github.galpiii.galpi.ai.support.AiPromptResources;
import com.openai.core.JsonValue;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class FeatureSpecPrompt {

    private static final String PROMPT_PATH = "ai/feature-spec-extraction-prompt.md";
    private static final String SCHEMA_PATH = "ai/feature-spec-extraction-schema.json";

    private String instructions;
    private ResponseFormatTextJsonSchemaConfig schemaConfig;

    @PostConstruct
    void load() {
        instructions = AiPromptResources.readText(PROMPT_PATH);
        schemaConfig = AiPromptResources.readSchema(SCHEMA_PATH);
    }

    public String instructions() {
        return instructions;
    }

    public ResponseFormatTextJsonSchemaConfig schemaConfig() {
        return schemaConfig;
    }

    Map<String, JsonValue> schemaProperties() {
        return schemaConfig.schema()._additionalProperties();
    }
}
