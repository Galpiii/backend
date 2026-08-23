package com.github.galpiii.galpi.domain.consent.repository;

import com.github.galpiii.galpi.domain.consent.entity.AiDataConsent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiDataConsentRepository extends JpaRepository<AiDataConsent, Long> {

    boolean existsByUserIdAndConsentVersion(Long userId, String consentVersion);

    Optional<AiDataConsent> findByUserIdAndConsentVersion(Long userId, String consentVersion);

    /** 가장 최근에 동의한 버전. 재동의가 필요한지 화면에 보여주는 데 쓴다. */
    Optional<AiDataConsent> findFirstByUserIdOrderByAgreedAtDescIdDesc(Long userId);
}
