package com.github.galpiii.galpi.domain.featurematch.worker;

import com.github.galpiii.galpi.ai.FeatureMatchingAiService;
import com.github.galpiii.galpi.ai.dto.FeatureMatchingResult;
import com.github.galpiii.galpi.ai.exception.FeatureMatchingInvalidResponseException;
import com.github.galpiii.galpi.ai.exception.FeatureMatchingAiException;
import com.github.galpiii.galpi.ai.exception.RetryableAiException;
import com.github.galpiii.galpi.ai.support.AiFailureKind;
import com.github.galpiii.galpi.domain.featurematch.exception.FeatureMatchInputTooLargeException;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchFailureCode;
import com.github.galpiii.galpi.domain.featurematch.service.FeatureMatchWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class FeatureMatchRunExecutor {

    private final FeatureMatchWriter writer;
    private final FeatureMatchingAiService ai;

    public void execute(long targetId, String token) {
        String stage = "PREPARE";
        try {
            FeatureMatchWriter.Prepared prepared = writer.prepare(targetId, token);
            if (prepared == null) {
                return;
            }
            // 네트워크 호출 동안 DB 트랜잭션과 행 잠금을 유지하지 않는다.
            stage = "AI_CALL";
            FeatureMatchingResult result = ai.match(prepared.input());
            stage = "COMPLETE";
            writer.complete(targetId, token, result);
        } catch (FeatureMatchingInvalidResponseException e) {
            log.warn("[기능대조] 응답 검증 실패 targetId={} stage={} rule={}", targetId, stage, e.getMessage());
            writer.fail(targetId, token, FeatureMatchFailureCode.AI_RESPONSE_INVALID, false);
        } catch (FeatureMatchInputTooLargeException e) {
            log.warn("[기능대조] 필수 입력 예산 초과 targetId={} stage={}", targetId, stage);
            writer.fail(targetId, token, FeatureMatchFailureCode.INPUT_TOO_LARGE, false);
        } catch (RetryableAiException e) {
            log.warn("[기능대조] 일시적 AI 오류 targetId={} type={}", targetId, e.getClass().getSimpleName());
            writer.fail(targetId, token, FeatureMatchFailureCode.AI_CALL_FAILED, true);
        } catch (FeatureMatchingAiException e) {
            boolean timeout = e.getKind() == AiFailureKind.BUDGET_EXHAUSTED;
            log.warn("[기능대조] AI 호출 실패 targetId={} kind={}", targetId, e.getKind());
            writer.fail(targetId, token, timeout ? FeatureMatchFailureCode.AI_TIMEOUT
                    : FeatureMatchFailureCode.AI_CALL_FAILED, timeout);
        } catch (RuntimeException e) {
            // SDK 응답/SQL에 포함된 원문 대신 예외 타입과 코드 위치를 남긴다.
            log.error("[기능대조] 내부 처리 실패 targetId={} stage={} type={} trace={}",
                    targetId, stage, e.getClass().getName(), e.getStackTrace());
            writer.fail(targetId, token, FeatureMatchFailureCode.INTERNAL_ERROR,
                    e instanceof TransientDataAccessException);
        }
    }
}
