package com.github.galpiii.galpi.domain.featurematch.support;

import com.github.galpiii.galpi.ai.dto.FeatureMatchingResult;
import com.github.galpiii.galpi.ai.dto.FeatureMatchingResult.Match;
import com.github.galpiii.galpi.ai.exception.FeatureMatchingInvalidResponseException;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.FeatureRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.RequirementRow;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("기능대조 응답 조각 검증")
class FeatureMatchResultValidatorTest {
    private final FeatureMatchResultValidator validator = new FeatureMatchResultValidator();
    private final List<FeatureRow> features = List.of(new FeatureRow(
            1, null, null, null, "가입", 0, FeatureReviewStatus.UNREVIEWED, null, null));
    private final List<RequirementRow> requirements = List.of(
            new RequirementRow(3, 1, "가입 처리", 0));

    @Test
    @DisplayName("외부 기능·외부 요구사항·중복 ID만 제외하고 유효한 연결은 보존한다")
    void salvagesValidFragments() {
        FeatureMatchingResult result = validator.validate(new FeatureMatchingResult(List.of(
                new Match(999L, "외부 기능", List.of()),
                new Match(1L, "가입 처리 근거", List.of(3L, 999L, 3L)),
                new Match(1L, "중복 기능", List.of(3L)))), features, requirements);
        assertThat(result.matches()).containsExactly(new Match(1L, "가입 처리 근거", List.of(3L)));
    }

    @Test
    @DisplayName("유효한 연결이 전혀 없는 잘못된 응답을 근거 없음으로 저장하지 않는다")
    void rejectsWhollyInvalidResult() {
        assertThatThrownBy(() -> validator.validate(new FeatureMatchingResult(List.of(
                new Match(999L, "외부 기능", List.of()))), features, requirements))
                .isInstanceOf(FeatureMatchingInvalidResponseException.class).hasMessage("NO_VALID_MATCHES");
    }

    @Test
    @DisplayName("의도적으로 비어 있는 연결 목록은 정상적인 근거 없음이다")
    void acceptsEmptyResult() {
        assertThat(validator.validate(new FeatureMatchingResult(List.of()), features, requirements).matches())
                .isEmpty();
    }
}
