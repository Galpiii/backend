package com.github.galpiii.galpi.domain.featurematch.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.galpiii.galpi.domain.collection.secret.SecretContentScanner;
import com.github.galpiii.galpi.domain.collection.secret.SecretPathRules;
import com.github.galpiii.galpi.domain.featurematch.config.FeatureMatchProperties;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.FeatureRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.FileRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.PrRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.RequirementRow;
import com.github.galpiii.galpi.domain.featurematch.exception.FeatureMatchInputTooLargeException;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("기능대조 입력 예산")
class FeatureMatchInputAssemblerTest {
    private final FeatureMatchQueryRepository repository = mock(FeatureMatchQueryRepository.class);
    private final List<FeatureRow> features = List.of(new FeatureRow(
            1, 2L, "회원", 0, "회원가입", 0, FeatureReviewStatus.UNREVIEWED, 1, 1));
    private final List<RequirementRow> requirements = List.of(
            new RequirementRow(3, 1, "가입 정보를 저장한다", 0));

    private FeatureMatchInputAssembler assembler(int budget) {
        return new FeatureMatchInputAssembler(repository, new SecretContentScanner(), new SecretPathRules(),
                new FeatureMatchProperties(true, Duration.ofSeconds(5), Duration.ofMinutes(10),
                        Duration.ofSeconds(5), 3, 4, budget));
    }

    @Test
    @DisplayName("큰 PR과 이스케이프 문자가 있어도 기능 목록을 보존하며 예산 안에서 자른다")
    void boundsLargePullRequest() {
        PrRow pr = mock(PrRow.class);
        when(pr.id()).thenReturn(10L);
        when(pr.body()).thenReturn("본문\n\"".repeat(10000));
        when(repository.commits(10)).thenReturn(Collections.nCopies(100, "커밋\n\"".repeat(200)));
        when(repository.files(10)).thenReturn(Collections.nCopies(300,
                new FileRow("src/" + "경로".repeat(150), "MODIFIED", 10, 2)));
        String input = assembler(2000).assemble(features, requirements, pr);
        assertThat(input).hasSizeLessThanOrEqualTo(2000)
                .contains("회원가입", "가입 정보를 저장한다");
    }

    @Test
    @DisplayName("비밀 경로와 비밀 문자열은 전달하지 않는다")
    void masksSecretsBeforeTruncation() {
        PrRow pr = mock(PrRow.class);
        when(pr.id()).thenReturn(10L);
        String token = "ghp_" + "a".repeat(40);
        when(pr.body()).thenReturn(token);
        when(repository.commits(10)).thenReturn(List.of(token));
        when(repository.files(10)).thenReturn(List.of(new FileRow(".env", "ADDED", 1, 0)));
        assertThat(assembler(2000).assemble(features, requirements, pr))
                .doesNotContain(token, ".env").contains("***");
    }

    @Test
    @DisplayName("전달되지 않은 커밋 뒷부분과 비밀 파일 변경은 입력 해시를 바꾸지 않는다")
    void ignoredCollectedDataDoesNotChangeInputHash() {
        PrRow pr = mock(PrRow.class);
        when(pr.id()).thenReturn(10L);
        FeatureMatchInputAssembler assembler = assembler(2000);
        when(repository.commits(10)).thenReturn(List.of("a".repeat(600)));
        when(repository.files(10)).thenReturn(List.of(new FileRow(".env", "ADDED", 1, 0)));
        var before = FeatureMatchSnapshot.pullRequestInput(assembler.assemble(features, requirements, pr));

        when(repository.commits(10)).thenReturn(List.of("a".repeat(500) + "다른 내용"));
        when(repository.files(10)).thenReturn(List.of(new FileRow(".env.local", "MODIFIED", 2, 1)));
        var after = FeatureMatchSnapshot.pullRequestInput(assembler.assemble(features, requirements, pr));

        assertThat(after.analysisHash()).isEqualTo(before.analysisHash());
        assertThat(after.sourceHash()).isEqualTo(before.sourceHash());
    }

    @Test
    @DisplayName("재분석 실패로 요약이 비어도 이전 입력 예산으로 원본 정보를 비교한다")
    void failedReanalysisKeepsOriginalInputBudget() {
        PrRow pr = mock(PrRow.class);
        when(pr.id()).thenReturn(10L);
        when(pr.summary()).thenReturn("요약".repeat(150));
        when(repository.commits(10)).thenReturn(List.of("커밋".repeat(200)));
        FeatureMatchInputAssembler assembler = assembler(2000);
        var previous = FeatureMatchSnapshot.pullRequestInput(assembler.assemble(features, requirements, pr));

        when(pr.summary()).thenReturn(null);
        var current = assembler.sourcePullRequest(pr, previous.sectionChars(), previous.analysisChars());

        assertThat(FeatureMatchSnapshot.sourceHash(current)).isEqualTo(previous.sourceHash());
    }

    @Test
    @DisplayName("필수 기능 목록 자체가 너무 크면 HTTP 예외가 아닌 도메인 예외로 거부한다")
    void rejectsOversizedMandatoryFeatures() {
        List<RequirementRow> oversized = List.of(
                new RequirementRow(3, 1, "요구사항".repeat(1000), 0));
        assertThatThrownBy(() -> assembler(1000).checkFeatureSize(features, oversized))
                .isInstanceOf(FeatureMatchInputTooLargeException.class);
    }

    @Test
    @DisplayName("기본 예산에서는 PR 본문을 3750자가 아니라 최대 8000자 보존한다")
    void defaultBodyBudgetIsEightThousand() throws Exception {
        PrRow pr = mock(PrRow.class);
        when(pr.body()).thenReturn("본문".repeat(5000));
        String input = assembler(120000).assemble(features, requirements, pr);
        String body = new ObjectMapper()
                .readTree(input).path("pullRequest").path("body").asText();
        assertThat(body).hasSize(8000).endsWith("…");
    }
}
