package com.github.galpiii.galpi.ai;

import com.github.galpiii.galpi.ai.support.AiPromptResources;
import com.openai.core.JsonValue;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * PR 요약 프롬프트와 응답 스키마.
 *
 * <p>기능명세서 쪽과 구조가 같지만 파일이 다르다. 두 프롬프트는 하는 일이 전혀 달라 -- 저쪽은
 * 문서에서 기능 목록을 뽑고 이쪽은 diff를 문장으로 옮긴다 -- 한 파일에 담으면 한쪽을 고칠
 * 때마다 다른 쪽 동작을 확인해야 한다.
 */
@Component
public class PullRequestSummaryPrompt {

    private static final String PROMPT_PATH = "ai/pr-summary-prompt.md";
    private static final String SCHEMA_PATH = "ai/pr-summary-schema.json";

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
