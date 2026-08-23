package com.github.galpiii.galpi.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.galpiii.galpi.ai.config.OpenAiProperties;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.DuplicateCandidate;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Feature;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Issue;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Requirement;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Section;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.SuggestedFeature;
import com.github.galpiii.galpi.domain.featurespec.support.FeatureExtractionResultNormalizer;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 OpenAI를 호출하는 실측용 테스트.
 *
 * <p>환경변수가 모두 있을 때만 실행되므로 평소 빌드와 CI에서는 건너뛴다. 크레딧이 소모되니
 * 필요할 때만 직접 돌린다.
 *
 * <pre>
 * OPENAI_API_KEY=sk-... FEATURE_SPEC_PDF=/경로/기능명세서.pdf \
 *   ./gradlew test --tests "*FeatureSpecAiRealCallTest" --rerun-tasks -i
 * </pre>
 *
 * <p>IntelliJ에서는 실행 구성의 Environment variables에 같은 두 값을 넣고 돌리면 된다.
 *
 * <p>Spring 컨텍스트를 띄우지 않는다. DB도 Redis도 필요 없고 OpenAI 통신만 확인하면 되기 때문이다.
 */
@DisplayName("실제 OpenAI 호출 — 실측")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "FEATURE_SPEC_PDF", matches = ".+")
class FeatureSpecAiRealCallTest {

    private static final Path RAW_RESULT_PATH = Path.of("build", "feature-spec-extraction-result.json");

    @Test
    @DisplayName("기능명세서 PDF를 분석해 결과를 전부 보여준다")
    void analyzeRealDocument() throws Exception {
        File pdf = new File(System.getenv("FEATURE_SPEC_PDF"));
        assertThat(pdf).exists();

        OpenAiProperties properties = new OpenAiProperties(
                System.getenv("OPENAI_API_KEY"),
                envOrDefault("OPENAI_MODEL", "gpt-5"),
                Long.parseLong(envOrDefault("OPENAI_MAX_OUTPUT_TOKENS", "64000")),
                Duration.parse(envOrDefault("OPENAI_TIMEOUT", "PT10M")),
                Integer.parseInt(envOrDefault("OPENAI_MAX_ATTEMPTS", "3")),
                Duration.ofSeconds(1),
                Duration.parse(envOrDefault("OPENAI_ANALYSIS_BUDGET", "PT15M")));

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .maxRetries(0)
                .timeout(properties.timeout())
                .build();

        FeatureSpecPrompt prompt = new FeatureSpecPrompt();
        prompt.load();

        long startedAt = System.currentTimeMillis();
        FeatureSpecExtractionResult raw =
                new FeatureSpecAiService(client, properties, prompt).analyze(pdf);
        long elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000;

        FeatureSpecExtractionResult normalized =
                new FeatureExtractionResultNormalizer().normalize(1L, raw);

        writeRawResult(raw);
        printSummary(pdf, elapsedSeconds, raw, normalized);
        printSections(normalized);
        printFeatures(normalized);

        assertThat(normalized.features()).isNotEmpty();
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    // 요약만으로 부족할 때 원본을 그대로 열어볼 수 있게 남긴다.
    private void writeRawResult(FeatureSpecExtractionResult raw) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Files.createDirectories(RAW_RESULT_PATH.getParent());
        Files.writeString(RAW_RESULT_PATH, objectMapper.writeValueAsString(raw));
    }

    private void printSummary(
            File pdf,
            long elapsedSeconds,
            FeatureSpecExtractionResult raw,
            FeatureSpecExtractionResult normalized
    ) {
        System.out.println("\n╔══════════════════════════════════════════════════════════════");
        System.out.println("║ 기능명세서 분석 실측");
        System.out.println("╚══════════════════════════════════════════════════════════════");
        System.out.printf("파일          : %s (%.1f MB)%n", pdf.getName(), pdf.length() / 1024.0 / 1024.0);
        System.out.printf("소요 시간     : %d초%n", elapsedSeconds);
        System.out.printf("섹션          : %d개%n", raw.sections().size());
        System.out.printf("기능          : %d개%n", raw.features().size());
        System.out.printf("세부 요구사항 : %d개%n", count(raw, feature -> feature.requirements().size()));
        System.out.printf("원본 JSON     : %s%n", RAW_RESULT_PATH.toAbsolutePath());

        System.out.println("\n─── 특이사항 유형별 ───");
        Map<String, Long> counts = raw.features().stream()
                .flatMap(feature -> feature.issues().stream())
                .collect(Collectors.groupingBy(Issue::type, LinkedHashMap::new, Collectors.counting()));

        if (counts.isEmpty()) {
            System.out.println("  (없음)");
        } else {
            counts.forEach((type, count) -> System.out.printf("  %s %d건%n", type, count));
        }

        System.out.println("\n─── Normalizer 전후 (줄었으면 프롬프트를 손볼 지점) ───");
        System.out.printf("  중복 후보   : %d → %d%n",
                count(raw, feature -> feature.duplicateCandidates().size()),
                count(normalized, feature -> feature.duplicateCandidates().size()));
        System.out.printf("  분리 제안   : %d → %d%n",
                count(raw, feature -> feature.splitSuggestion() == null ? 0 : 1),
                count(normalized, feature -> feature.splitSuggestion() == null ? 0 : 1));
        System.out.printf("  특이사항    : %d → %d%n",
                count(raw, feature -> feature.issues().size()),
                count(normalized, feature -> feature.issues().size()));
        System.out.printf("  미분류 기능 : %d → %d%n",
                count(raw, feature -> feature.section() == null ? 1 : 0),
                count(normalized, feature -> feature.section() == null ? 1 : 0));
    }

    private void printSections(FeatureSpecExtractionResult result) {
        System.out.println("\n╔══ 섹션 ══════════════════════════════════════════════════════");

        for (Section section : result.sections()) {
            long featureCount = result.features().stream()
                    .filter(feature -> section.title().equals(feature.section()))
                    .count();

            System.out.printf("%n▸ %s (기능 %d개)%n", section.title(), featureCount);
            System.out.printf("    원문 제목 : %s%n", orDash(section.sourceTitle()));
            System.out.printf("    원문 페이지: %s%n", pageRange(section.pageStart(), section.pageEnd()));
        }

        long unclassified = result.features().stream()
                .filter(feature -> feature.section() == null)
                .count();

        if (unclassified > 0) {
            System.out.printf("%n▸ (미분류) 기능 %d개%n", unclassified);
        }
    }

    private void printFeatures(FeatureSpecExtractionResult result) {
        Map<String, String> nameByExtractionId = result.features().stream()
                .collect(Collectors.toMap(Feature::extractionId, Feature::name, (a, b) -> a));

        System.out.println("\n╔══ 기능 ══════════════════════════════════════════════════════");

        for (Feature feature : result.features()) {
            System.out.printf("%n▸ %s%n", feature.name());
            System.out.printf("    섹션   : %s%n", orDash(feature.section()));
            System.out.printf("    원문   : p.%s%n",
                    pageRange(feature.source().pageStart(), feature.source().pageEnd()));

            printRequirements(feature);
            printIssues(feature);
            printDuplicateCandidates(feature, nameByExtractionId);
            printSplitSuggestion(feature);
        }
    }

    private void printRequirements(Feature feature) {
        if (feature.requirements().isEmpty()) {
            System.out.println("    요구사항: (없음)");
            return;
        }

        System.out.printf("    요구사항 %d개%n", feature.requirements().size());

        for (int index = 0; index < feature.requirements().size(); index++) {
            Requirement requirement = feature.requirements().get(index);
            System.out.printf("      [%d] %s%n", index, requirement.content());
            System.out.printf("          원문: %s%n", requirement.originalText());
        }
    }

    private void printIssues(Feature feature) {
        if (feature.issues().isEmpty()) {
            return;
        }

        System.out.println("    특이사항");
        feature.issues().forEach(issue ->
                System.out.printf("      · %s%n        %s%n", issue.type(), issue.description()));
    }

    private void printDuplicateCandidates(Feature feature, Map<String, String> nameByExtractionId) {
        if (feature.duplicateCandidates().isEmpty()) {
            return;
        }

        System.out.println("    중복 후보");

        for (DuplicateCandidate candidate : feature.duplicateCandidates()) {
            System.out.printf("      · 대상: %s%n",
                    nameByExtractionId.getOrDefault(candidate.targetExtractionId(), "(알 수 없음)"));
            System.out.printf("        사유: %s%n", candidate.reason());
            System.out.printf("        병합 제안: %s  /  섹션: %s%n",
                    candidate.suggestedMergedName(), candidate.suggestedSection());
        }
    }

    private void printSplitSuggestion(Feature feature) {
        if (feature.splitSuggestion() == null) {
            return;
        }

        System.out.printf("    분리 제안 %d개%n", feature.splitSuggestion().suggestedFeatures().size());

        for (SuggestedFeature suggested : feature.splitSuggestion().suggestedFeatures()) {
            System.out.printf("      · %s  (섹션: %s)%n",
                    suggested.suggestedName(), suggested.suggestedSection());

            // 어떤 요구사항이 어디로 가는지가 이 제안의 실질이다.
            for (Integer index : suggested.requirementIndexes()) {
                System.out.printf("          [%d] %s%n",
                        index, feature.requirements().get(index).content());
            }
        }
    }

    private String pageRange(Integer start, Integer end) {
        if (start == null && end == null) {
            return "-";
        }

        return start + "-" + end;
    }

    private String orDash(String value) {
        return value == null ? "-" : value;
    }

    private int count(FeatureSpecExtractionResult result, ToIntFunction<Feature> counter) {
        return result.features().stream().mapToInt(counter).sum();
    }
}
