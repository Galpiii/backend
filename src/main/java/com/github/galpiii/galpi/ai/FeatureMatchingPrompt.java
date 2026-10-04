package com.github.galpiii.galpi.ai;

import com.github.galpiii.galpi.ai.support.AiPromptResources;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 프롬프트 누락을 첫 실행이 아닌 기동 시점에 발견한다. 다른 AI 작업과 리소스를 공유하지 않는다.
 */
@Component
public class FeatureMatchingPrompt {

    private String instructions;
    private ResponseFormatTextJsonSchemaConfig schemaConfig;

    @PostConstruct
    void load() {
        instructions = AiPromptResources.readText("ai/feature-matching-prompt.md");
        schemaConfig = AiPromptResources.readSchema("ai/feature-matching-schema.json");
    }

    public String instructions() {
        return instructions;
    }

    public ResponseFormatTextJsonSchemaConfig schemaConfig() {
        return schemaConfig;
    }
}
