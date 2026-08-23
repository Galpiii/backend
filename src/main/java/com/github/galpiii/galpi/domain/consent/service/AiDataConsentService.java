package com.github.galpiii.galpi.domain.consent.service;

import com.github.galpiii.galpi.domain.consent.AiDataNotice;
import com.github.galpiii.galpi.domain.consent.config.ConsentProperties;
import com.github.galpiii.galpi.domain.consent.dto.AiDataConsentStatusResponse;
import com.github.galpiii.galpi.domain.consent.entity.AiDataConsent;
import com.github.galpiii.galpi.domain.consent.repository.AiDataConsentRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.ForbiddenException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 외부 LLM 전송 동의.
 *
 * <p>저장소 접근 범위와 외부 AI 전송은 별개 사안이다. GitHub 연결이 되어 있다고 해서 코드를
 * 외부로 보내도 된다는 뜻이 아니므로, 첫 분석 실행 전에 여기서 따로 동의를 받는다.
 *
 * <p>동의는 시각이 아니라 <b>버전</b>으로 관리한다. 제공자나 보관 정책이 바뀌면
 * {@code galpi.consent.ai-data-version}을 올리는 것만으로 기존 사용자 전체가 재동의 대상이
 * 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiDataConsentService {

    private final AiDataConsentRepository consentRepository;
    private final AiDataConsentWriter writer;
    private final ConsentProperties properties;

    @Transactional(readOnly = true)
    public AiDataConsentStatusResponse status(Long userId) {
        String current = properties.aiDataVersion();
        Optional<AiDataConsent> latest =
                consentRepository.findFirstByUserIdOrderByAgreedAtDescIdDesc(userId);
        boolean agreed = consentRepository.existsByUserIdAndConsentVersion(userId, current);

        return new AiDataConsentStatusResponse(
                current,
                agreed,
                latest.map(AiDataConsent::getConsentVersion).orElse(null),
                latest.map(AiDataConsent::getAgreedAt).orElse(null),
                AiDataNotice.TEXT);
    }

    /**
     * 현재 버전에 동의한다.
     *
     * <p>화면이 보낸 버전이 현재 버전과 다르면 거부한다. 옛 문구를 띄운 탭이 남아 있는 상태로
     * 동의 버튼이 눌리면, 사용자가 읽지 않은 내용에 동의한 것으로 기록된다.
     *
     * <p>같은 버전에 두 번 눌러도 결과는 같다(멱등). 유니크 제약이 최종적으로 막고, 경쟁에서
     * 진 요청은 이미 저장된 행을 그대로 돌려준다. 메서드 전체에 트랜잭션을 걸지 않는 것은
     * 그 때문이다 — 제약 위반이 난 트랜잭션 안에서는 결과를 다시 읽을 수 없다.
     */
    public AiDataConsentStatusResponse agree(Long userId, String consentVersion) {
        String current = properties.aiDataVersion();
        if (!current.equals(consentVersion)) {
            log.info("[동의] 현재 버전이 아닌 고지에 동의를 시도 userId={} 요청={} 현재={}",
                    userId, consentVersion, current);
            throw new ConflictException(ErrorCode.AI_DATA_CONSENT_VERSION_MISMATCH);
        }

        if (!consentRepository.existsByUserIdAndConsentVersion(userId, current)) {
            try {
                writer.save(userId, current);
                log.info("[동의] 외부 AI 전송에 동의 userId={} version={}", userId, current);
            } catch (DataIntegrityViolationException e) {
                // 같은 사용자의 동의 요청 둘이 겹쳤다. 먼저 커밋된 행이 그대로 정답이다.
                // 저장을 별도 트랜잭션으로 떼어 뒀기에 여기서 삼키고 상태를 다시 읽을 수 있다.
                log.info("[동의] 동시 동의 요청을 하나로 합친다 userId={} version={}", userId, current);
            }
        }
        return status(userId);
    }

    /**
     * 동의 없이 분석이 시작되는 것을 막는다.
     *
     * <p>이 검사가 통과하지 못하면 {@code analysis_runs}를 만들지 않는다. 작업이 만들어지고
     * 나면 워커는 사용자 세션 없이 돌기 때문에, 여기가 전송 전 마지막 관문이다.
     */
    @Transactional(readOnly = true)
    public void requireAgreed(Long userId) {
        String current = properties.aiDataVersion();
        if (consentRepository.existsByUserIdAndConsentVersion(userId, current)) {
            return;
        }
        log.info("[동의] 외부 AI 전송 동의가 없어 분석을 막는다 userId={} version={}", userId, current);
        throw new ForbiddenException(ErrorCode.AI_DATA_CONSENT_REQUIRED);
    }
}
