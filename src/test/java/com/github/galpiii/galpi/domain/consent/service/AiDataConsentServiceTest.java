package com.github.galpiii.galpi.domain.consent.service;

import com.github.galpiii.galpi.domain.consent.config.ConsentProperties;
import com.github.galpiii.galpi.domain.consent.dto.AiDataConsentStatusResponse;
import com.github.galpiii.galpi.domain.consent.entity.AiDataConsent;
import com.github.galpiii.galpi.domain.consent.repository.AiDataConsentRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.ForbiddenException;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AiDataConsentService — 외부 AI 전송 동의")
class AiDataConsentServiceTest {

    private static final long USER_ID = 7L;
    private static final String CURRENT = "2026-08-23";
    private static final String OLD = "2025-01-01";

    @Mock
    private AiDataConsentRepository consentRepository;
    @Mock
    private AiDataConsentWriter writer;

    private AiDataConsentService service;

    @BeforeEach
    void setUp() {
        service = new AiDataConsentService(consentRepository, writer,
                new ConsentProperties(CURRENT));
    }

    @Test
    @DisplayName("현재 버전에 동의하지 않았으면 분석을 막는다")
    void blocksAnalysisWithoutConsent() {
        given(consentRepository.existsByUserIdAndConsentVersion(USER_ID, CURRENT))
                .willReturn(false);

        assertThatThrownBy(() -> service.requireAgreed(USER_ID))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((GlobalException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_DATA_CONSENT_REQUIRED);
    }

    @Test
    @DisplayName("현재 버전에 동의했으면 통과한다")
    void allowsAnalysisWithConsent() {
        given(consentRepository.existsByUserIdAndConsentVersion(USER_ID, CURRENT))
                .willReturn(true);

        service.requireAgreed(USER_ID);
    }

    @Test
    @DisplayName("정책 버전이 올라가면 이전 버전에만 동의한 사용자도 막힌다 — 재동의")
    void requiresReconsentAfterVersionBump() {
        given(consentRepository.existsByUserIdAndConsentVersion(USER_ID, CURRENT))
                .willReturn(false);
        given(consentRepository.findFirstByUserIdOrderByAgreedAtDescIdDesc(USER_ID))
                .willReturn(Optional.of(consentOf(OLD)));

        assertThatThrownBy(() -> service.requireAgreed(USER_ID))
                .isInstanceOf(ForbiddenException.class);

        AiDataConsentStatusResponse status = service.status(USER_ID);
        assertThat(status.agreed()).isFalse();
        // 처음 동의가 아니라 재동의라는 것을 화면이 구분할 수 있어야 한다.
        assertThat(status.agreedVersion()).isEqualTo(OLD);
        assertThat(status.currentVersion()).isEqualTo(CURRENT);
    }

    @Test
    @DisplayName("현재 버전이 아닌 고지에 동의하면 거부한다 — 읽지 않은 내용에 동의시킬 수 없다")
    void rejectsStaleVersion() {
        assertThatThrownBy(() -> service.agree(USER_ID, OLD))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((GlobalException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_DATA_CONSENT_VERSION_MISMATCH);

        verify(writer, never()).save(any(), any());
    }

    @Test
    @DisplayName("같은 버전에 두 번 동의해도 행을 더 만들지 않는다")
    void isIdempotent() {
        given(consentRepository.existsByUserIdAndConsentVersion(USER_ID, CURRENT))
                .willReturn(true);

        AiDataConsentStatusResponse status = service.agree(USER_ID, CURRENT);

        verify(writer, never()).save(any(), any());
        assertThat(status.agreed()).isTrue();
    }

    @Test
    @DisplayName("동시에 눌린 동의 요청이 겹쳐도 실패로 만들지 않는다")
    void survivesConcurrentAgreement() {
        given(consentRepository.existsByUserIdAndConsentVersion(USER_ID, CURRENT))
                .willReturn(false, true);
        willThrow(new DataIntegrityViolationException("duplicate key"))
                .given(writer).save(any(), any());

        AiDataConsentStatusResponse status = service.agree(USER_ID, CURRENT);

        assertThat(status.agreed()).isTrue();
    }

    @Test
    @DisplayName("고지 문구를 함께 내린다 — 버전과 문구가 어긋나면 동의 기록이 근거를 잃는다")
    void servesNoticeText() {
        AiDataConsentStatusResponse status = service.status(USER_ID);

        assertThat(status.notice())
                .contains("외부 AI 서비스로 전송")
                // 완전한 보호를 약속하지 않는다는 한계 고지가 빠지면 안 된다.
                .contains("모든 민감정보 탐지를 보장하지는 않습니다");
    }

    private static AiDataConsent consentOf(String version) {
        return AiDataConsent.agree(
                User.ofGithub(999L, "wb", "wb", null, "https://avatar"), version);
    }
}
