package com.github.galpiii.galpi.domain.consent.service;

import com.github.galpiii.galpi.domain.consent.entity.AiDataConsent;
import com.github.galpiii.galpi.domain.consent.repository.AiDataConsentRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동의 한 줄을 적는 짧은 트랜잭션.
 *
 * <p>저장을 따로 떼어 둔 이유는 유니크 충돌 때문이다. 제약 위반이 나면 그 트랜잭션은 이미
 * 중단된 상태라, 같은 트랜잭션에서 상태를 다시 읽으려 하면 "current transaction is aborted"로
 * 또 터진다. 저장만 독립 트랜잭션으로 끝내야 호출자가 충돌을 삼키고 결과를 다시 읽을 수 있다.
 */
@Component
@RequiredArgsConstructor
class AiDataConsentWriter {

    private final AiDataConsentRepository consentRepository;
    private final UserRepository userRepository;

    @Transactional
    void save(Long userId, String consentVersion, String noticeHash) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHORIZED));
        consentRepository.saveAndFlush(AiDataConsent.agree(user, consentVersion, noticeHash));
    }
}
