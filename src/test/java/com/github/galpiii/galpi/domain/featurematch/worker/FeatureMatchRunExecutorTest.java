package com.github.galpiii.galpi.domain.featurematch.worker;

import com.github.galpiii.galpi.ai.FeatureMatchingAiService;
import com.github.galpiii.galpi.ai.dto.FeatureMatchingResult;
import com.github.galpiii.galpi.ai.exception.FeatureMatchingAiException;
import com.github.galpiii.galpi.ai.exception.FeatureMatchingInvalidResponseException;
import com.github.galpiii.galpi.ai.exception.RetryableAiException;
import com.github.galpiii.galpi.ai.support.AiFailureKind;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchFailureCode;
import com.github.galpiii.galpi.domain.featurematch.service.FeatureMatchWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("기능대조 실행기 오류 분류")
class FeatureMatchRunExecutorTest {
    private final FeatureMatchWriter writer = mock(FeatureMatchWriter.class);
    private final FeatureMatchingAiService ai = mock(FeatureMatchingAiService.class);
    private final FeatureMatchRunExecutor executor = new FeatureMatchRunExecutor(writer, ai);

    private void prepared() {
        when(writer.prepare(1, "token")).thenReturn(new FeatureMatchWriter.Prepared("input"));
    }

    @Test
    @DisplayName("준비 단계의 DB 일시 오류는 AI 오류로 기록하지 않는다")
    void retriesTransientDatabaseError() {
        when(writer.prepare(1, "token")).thenThrow(new TransientDataAccessResourceException("db"));
        executor.execute(1, "token");
        verify(writer).fail(1, "token", FeatureMatchFailureCode.INTERNAL_ERROR, true);
        verifyNoInteractions(ai);
    }

    @Test
    @DisplayName("저장 단계의 코드 오류는 내부 오류로 기록하고 반복하지 않는다")
    void doesNotMisclassifyCompletionBug() {
        prepared();
        FeatureMatchingResult result = new FeatureMatchingResult(List.of());
        when(ai.match("input")).thenReturn(result);
        doThrow(new NullPointerException("bug")).when(writer).complete(1, "token", result);
        executor.execute(1, "token");
        verify(writer).fail(1, "token", FeatureMatchFailureCode.INTERNAL_ERROR, false);
    }

    @Test
    @DisplayName("AI 영구 오류는 재시도하지 않는다")
    void permanentAiFailure() {
        prepared();
        when(ai.match("input")).thenThrow(new FeatureMatchingAiException(AiFailureKind.CALL_FAILED, "400", null));
        executor.execute(1, "token");
        verify(writer).fail(1, "token", FeatureMatchFailureCode.AI_CALL_FAILED, false);
    }

    @Test
    @DisplayName("AI 시간 예산 초과는 별도 코드로 재시도한다")
    void timeout() {
        prepared();
        when(ai.match("input")).thenThrow(new FeatureMatchingAiException(AiFailureKind.BUDGET_EXHAUSTED, "timeout", null));
        executor.execute(1, "token");
        verify(writer).fail(1, "token", FeatureMatchFailureCode.AI_TIMEOUT, true);
    }

    @Test
    @DisplayName("AI 일시 오류만 호출 실패로 재시도한다")
    void retriesAiFailure() {
        prepared();
        when(ai.match("input")).thenThrow(new RetryableAiException("temporary"));
        executor.execute(1, "token");
        verify(writer).fail(1, "token", FeatureMatchFailureCode.AI_CALL_FAILED, true);
    }

    @Test
    @DisplayName("유효하지 않은 AI 응답은 재시도하지 않는다")
    void invalidResponse() {
        prepared();
        when(ai.match("input")).thenThrow(new FeatureMatchingInvalidResponseException("INVALID_JSON"));
        executor.execute(1, "token");
        verify(writer).fail(1, "token", FeatureMatchFailureCode.AI_RESPONSE_INVALID, false);
    }
}
