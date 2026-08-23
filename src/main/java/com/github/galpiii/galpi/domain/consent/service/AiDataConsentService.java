package com.github.galpiii.galpi.domain.consent.service;

import com.github.galpiii.galpi.domain.consent.AiDataNotice;
import com.github.galpiii.galpi.domain.consent.config.ConsentProperties;
import com.github.galpiii.galpi.domain.consent.dto.AiDataConsentStatusResponse;
import com.github.galpiii.galpi.domain.consent.entity.AiDataConsent;
import com.github.galpiii.galpi.domain.consent.repository.AiDataConsentRepository;
import com.github.galpiii.galpi.domain.consent.exception.AiDataConsentRequiredException;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 외부 LLM 전송 동의.
 *
 * <p>저장소 접근 범위와 외부 AI 전송은 별개 사안이다. GitHub 연결이 되어 있다고 해서 코드를
 * 외부로 보내도 된다는 뜻이 아니므로, 첫 분석 실행 전에 여기서 따로 동의를 받는다.
 *
 * <p>동의는 시각이 아니라 <b>버전과 문구</b>로 관리한다. 정책이 바뀌면 {@link AiDataNotice}에
 * 새 버전과 문구를 더하는 것만으로 기존 사용자 전체가 재동의 대상이 된다.
 *
 * <p>판정에 해시까지 쓰는 것이 요점이다. 버전은 이름일 뿐이라, 버전을 올리지 않고 문구만
 * 고치면 사용자가 읽은 적 없는 내용에 동의한 것으로 남는다. 해시가 어긋나면 동의로 치지 않고,
 * 같은 버전에 다른 문구가 이미 기록돼 있으면 <b>배포 사고</b>로 보고 크게 실패시킨다 — 조용히
 * 넘기면 사용자는 재동의도 못 한 채 분석만 막힌다.
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
        // 한 번만 읽는다. 두 번 물으면 그 사이의 커밋 때문에 서로 모순되는 응답이 나온다.
        List<AiDataConsent> history = consentRepository.findAllByUserIdOrderByAgreedAtDescIdDesc(userId);
        AiDataConsent latest = history.isEmpty() ? null : history.getFirst();
        String currentHash = AiDataNotice.hash(current);
        boolean agreed = history.stream()
                .anyMatch(consent -> current.equals(consent.getConsentVersion())
                        && currentHash.equals(consent.getNoticeHash()));

        return new AiDataConsentStatusResponse(
                current,
                agreed,
                latest == null ? null : latest.getConsentVersion(),
                latest == null ? null : latest.getAgreedAt(),
                AiDataNotice.text(current));
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

        String noticeHash = AiDataNotice.hash(current);
        if (!consentRepository.existsByUserIdAndConsentVersionAndNoticeHash(
                userId, current, noticeHash)) {
            try {
                writer.save(userId, current, noticeHash);
                log.info("[동의] 외부 AI 전송에 동의 userId={} version={}", userId, current);
            } catch (DataIntegrityViolationException e) {
                // 무결성 위반은 FK나 제약 불일치로도 난다. 그것까지 삼키면 진짜 고장을
                // agreed=false인 200 응답으로 감추므로, 실제로 같은 버전 행이 있는지부터 본다.
                AiDataConsent stored = consentRepository
                        .findByUserIdAndConsentVersion(userId, current)
                        .orElseThrow(() -> e);
                requireSameNotice(userId, current, noticeHash, stored);
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
        if (consentRepository.existsByUserIdAndConsentVersionAndNoticeHash(
                userId, current, AiDataNotice.hash(current))) {
            return;
        }
        log.info("[동의] 외부 AI 전송 동의가 없어 분석을 막는다 userId={} version={}", userId, current);
        throw new AiDataConsentRequiredException();
    }

    /**
     * 유니크 충돌이 "같은 문구에 두 번 동의"인지 확인한다.
     *
     * <p>같은 버전에 다른 문구가 이미 기록돼 있다면 버전을 올리지 않고 문구를 고친 것이다.
     * 이 상태로는 새 동의를 넣을 수도(제약이 막는다), 기존 동의를 믿을 수도 없다. 사용자
     * 잘못이 아니므로 4xx로 돌려주지 않고, 운영이 알아채도록 크게 실패시킨다.
     */
    private void requireSameNotice(Long userId, String current, String noticeHash,
                                   AiDataConsent stored) {
        if (noticeHash.equals(stored.getNoticeHash())) {
            return;
        }
        log.error("[동의] 같은 버전에 다른 문구가 기록돼 있다. 버전을 올리지 않고 고지를 바꾼 "
                        + "배포로 보인다. AiDataNotice에 새 버전을 추가해야 한다 "
                        + "userId={} version={} 기록된해시={} 현재해시={}",
                userId, current, stored.getNoticeHash(), noticeHash);
        throw new GlobalException(ErrorCode.AI_DATA_CONSENT_NOTICE_CONFLICT);
    }
}
