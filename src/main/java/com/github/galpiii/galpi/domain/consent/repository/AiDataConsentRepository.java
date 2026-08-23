package com.github.galpiii.galpi.domain.consent.repository;

import com.github.galpiii.galpi.domain.consent.entity.AiDataConsent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiDataConsentRepository extends JpaRepository<AiDataConsent, Long> {

    /**
     * 이 버전의 <b>이 문구</b>에 동의했는지.
     *
     * <p>버전만 보면 같은 이름 아래 문구가 바뀌었을 때 옛 동의를 그대로 인정하게 된다. 해시가
     * 어긋나면 사용자가 읽은 것과 지금 보내려는 것이 다르다는 뜻이라 동의로 치지 않는다.
     */
    boolean existsByUserIdAndConsentVersionAndNoticeHash(Long userId, String consentVersion,
                                                         String noticeHash);

    Optional<AiDataConsent> findByUserIdAndConsentVersion(Long userId, String consentVersion);

    /**
     * 이 사용자의 동의 이력 전체. 최신이 앞이다.
     *
     * <p>"현재 버전에 동의했는가"와 "마지막으로 동의한 버전"을 따로 물으면 두 질문 사이에
     * 동의가 커밋될 수 있다. 그러면 agreed=true인데 agreedVersion=null인, 있을 수 없는 응답이
     * 나온다. 한 번에 읽어 같은 스냅샷에서 둘 다 답한다 — 사용자당 버전 수만큼이라 행 수가
     * 문제 될 규모가 아니다.
     */
    List<AiDataConsent> findAllByUserIdOrderByAgreedAtDescIdDesc(Long userId);
}
