package com.github.galpiii.galpi.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FeatureSpecPrompt — 프롬프트와 응답 스키마")
class FeatureSpecPromptTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FeatureSpecPrompt prompt;

    @BeforeEach
    void setUp() {
        prompt = new FeatureSpecPrompt();
        prompt.load();
    }

    @Test
    @DisplayName("프롬프트 리소스를 읽어 온다")
    void loadsInstructions() {
        assertThat(prompt.instructions()).contains("기능명세서");
    }

    @Test
    @DisplayName("스키마의 name과 strict를 SDK 설정으로 옮긴다")
    void mapsSchemaNameAndStrict() {
        assertThat(prompt.schemaConfig().name()).isEqualTo("feature_specification_analysis");
        assertThat(prompt.schemaConfig().strict()).contains(true);
    }

    @Test
    @DisplayName("스키마 본문을 손대지 않고 그대로 넘긴다")
    void keepsSchemaBodyIntact() {
        assertThat(prompt.schemaProperties())
                .containsKeys("type", "properties", "required", "additionalProperties");
    }

    /**
     * 스키마 파일과 DTO는 손으로 맞춘 것이라 한쪽만 고치면 조용히 어긋난다. 전 필드를 채운
     * 샘플을 역직렬화해 두 정의가 같은 모양을 가리키는지 확인한다.
     */
    @Test
    @DisplayName("스키마 전 필드를 채운 응답을 DTO로 빠짐없이 읽는다")
    void deserializesFullSchemaSample() throws Exception {
        String json = """
                {
                  "sections": [
                    {
                      "title": "회원 및 인증",
                      "sourceTitle": "4.1 회원 및 인증",
                      "pageStart": 3,
                      "pageEnd": 8
                    },
                    {
                      "title": "의미상 새로 묶은 분류",
                      "sourceTitle": null,
                      "pageStart": null,
                      "pageEnd": null
                    }
                  ],
                  "features": [
                    {
                      "extractionId": "f1",
                      "name": "커뮤니티 기능",
                      "section": "회원 및 인증",
                      "requirements": [
                        { "content": "게시글을 작성한다.", "originalText": "사용자는 게시글을 작성할 수 있다." },
                        { "content": "댓글을 작성한다.", "originalText": "게시글에 댓글을 작성할 수 있다." }
                      ],
                      "source": { "pageStart": 9, "pageEnd": 9 },
                      "issues": [
                        { "type": "SPLIT_RECOMMENDED", "description": "독립적인 기능이 묶여 있다." },
                        { "type": "DUPLICATE_SUSPECTED", "description": "다른 기능과 겹친다." }
                      ],
                      "duplicateCandidates": [
                        {
                          "targetExtractionId": "f2",
                          "reason": "요구사항이 사실상 같다.",
                          "suggestedMergedName": "게시글 관리",
                          "suggestedSection": "회원 및 인증"
                        }
                      ],
                      "splitSuggestion": {
                        "suggestedFeatures": [
                          {
                            "suggestedName": "게시글 관리",
                            "requirementIndexes": [0],
                            "suggestedSection": "회원 및 인증"
                          },
                          {
                            "suggestedName": "댓글 관리",
                            "requirementIndexes": [1],
                            "suggestedSection": "회원 및 인증"
                          }
                        ]
                      }
                    },
                    {
                      "extractionId": "f2",
                      "name": "관리자 권한 관리",
                      "section": null,
                      "requirements": [],
                      "source": { "pageStart": null, "pageEnd": null },
                      "issues": [
                        { "type": "MISSING_REQUIREMENTS", "description": "추후 작성으로만 적혀 있다." }
                      ],
                      "duplicateCandidates": [],
                      "splitSuggestion": null
                    }
                  ]
                }
                """;

        FeatureSpecExtractionResult result =
                objectMapper.readValue(json, FeatureSpecExtractionResult.class);

        assertThat(result.sections()).hasSize(2);
        assertThat(result.sections().getFirst().sourceTitle()).isEqualTo("4.1 회원 및 인증");
        assertThat(result.sections().getLast().sourceTitle()).isNull();
        assertThat(result.sections().getLast().pageStart()).isNull();

        FeatureSpecExtractionResult.Feature community = result.features().getFirst();
        assertThat(community.extractionId()).isEqualTo("f1");
        assertThat(community.section()).isEqualTo("회원 및 인증");
        assertThat(community.requirements()).hasSize(2);
        assertThat(community.requirements().getFirst().originalText())
                .isEqualTo("사용자는 게시글을 작성할 수 있다.");
        assertThat(community.source().pageStart()).isEqualTo(9);
        assertThat(community.issues()).extracting(FeatureSpecExtractionResult.Issue::type)
                .containsExactly("SPLIT_RECOMMENDED", "DUPLICATE_SUSPECTED");
        assertThat(community.duplicateCandidates().getFirst().targetExtractionId()).isEqualTo("f2");
        assertThat(community.splitSuggestion().suggestedFeatures())
                .extracting(FeatureSpecExtractionResult.SuggestedFeature::requirementIndexes)
                .containsExactly(List.of(0), List.of(1));

        FeatureSpecExtractionResult.Feature admin = result.features().getLast();
        assertThat(admin.section()).isNull();
        assertThat(admin.requirements()).isEmpty();
        assertThat(admin.duplicateCandidates()).isEmpty();
        assertThat(admin.splitSuggestion()).isNull();
        assertThat(admin.source().pageStart()).isNull();
    }
}
