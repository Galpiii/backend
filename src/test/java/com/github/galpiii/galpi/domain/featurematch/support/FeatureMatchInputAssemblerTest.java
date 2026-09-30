package com.github.galpiii.galpi.domain.featurematch.support;

import com.github.galpiii.galpi.domain.collection.secret.SecretContentScanner;
import com.github.galpiii.galpi.domain.collection.secret.SecretPathRules;
import com.github.galpiii.galpi.domain.featurematch.config.FeatureMatchProperties;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchFeatureRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchFileRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchPullRequestRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRequirementRow;
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
    private final List<FeatureMatchFeatureRow> features = List.of(new FeatureMatchFeatureRow(
            1, 2L, "회원", 0, "회원가입", 0, FeatureReviewStatus.UNREVIEWED, 1, 1));
    private final List<FeatureMatchRequirementRow> requirements = List.of(
            new FeatureMatchRequirementRow(3, 1, "가입 정보를 저장한다", 0));

    private FeatureMatchInputAssembler assembler(int budget) {
        return new FeatureMatchInputAssembler(repository, new SecretContentScanner(), new SecretPathRules(),
                new FeatureMatchProperties(true, Duration.ofSeconds(5), Duration.ofMinutes(10),
                        Duration.ofSeconds(5), 3, 4, budget));
    }

    @Test
    @DisplayName("큰 PR과 이스케이프 문자가 있어도 기능 목록을 보존하며 예산 안에서 자른다")
    void boundsLargePullRequest() {
        FeatureMatchPullRequestRow pr = mock(FeatureMatchPullRequestRow.class);
        when(pr.id()).thenReturn(10L);
        when(pr.body()).thenReturn("본문\n\"".repeat(10000));
        when(repository.commits(10)).thenReturn(Collections.nCopies(100, "커밋\n\"".repeat(200)));
        when(repository.files(10)).thenReturn(Collections.nCopies(300,
                new FeatureMatchFileRow("src/" + "경로".repeat(150), "MODIFIED", 10, 2)));
        String input = assembler(2000).assemble(features, requirements, pr);
        assertThat(input).hasSizeLessThanOrEqualTo(2000)
                .contains("회원가입", "가입 정보를 저장한다");
    }

    @Test
    @DisplayName("비밀 경로와 비밀 문자열은 전달하지 않는다")
    void masksSecretsBeforeTruncation() {
        FeatureMatchPullRequestRow pr = mock(FeatureMatchPullRequestRow.class);
        when(pr.id()).thenReturn(10L);
        String token = "ghp_" + "a".repeat(40);
        when(pr.body()).thenReturn(token);
        when(repository.commits(10)).thenReturn(List.of(token));
        when(repository.files(10)).thenReturn(List.of(new FeatureMatchFileRow(".env", "ADDED", 1, 0)));
        assertThat(assembler(2000).assemble(features, requirements, pr))
                .doesNotContain(token, ".env").contains("***");
    }

    @Test
    @DisplayName("필수 기능 목록 자체가 너무 크면 HTTP 예외가 아닌 도메인 예외로 거부한다")
    void rejectsOversizedMandatoryFeatures() {
        List<FeatureMatchRequirementRow> oversized = List.of(
                new FeatureMatchRequirementRow(3, 1, "요구사항".repeat(1000), 0));
        assertThatThrownBy(() -> assembler(1000).checkFeatureSize(features, oversized))
                .isInstanceOf(FeatureMatchInputTooLargeException.class);
    }
}
