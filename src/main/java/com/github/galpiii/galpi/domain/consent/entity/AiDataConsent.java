package com.github.galpiii.galpi.domain.consent.entity;

import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 선별된 코드와 PR 데이터를 외부 AI 서비스로 보내는 것에 대한 사용자 동의.
 *
 * <p>동의한 <b>버전</b>을 함께 남기는 것이 이 테이블의 요점이다. 시각만 남기면 제공자나 보관
 * 정책이 바뀐 뒤에 누구에게 재동의를 받아야 하는지 알 수 없다.
 *
 * <p>이전 버전 행은 지우지 않는다. 재동의는 새 행을 더하는 것이고, "그때 무엇에 동의했는지"는
 * 나중에 되짚을 수 있어야 한다.
 */
@Entity
@Getter
@Table(name = "ai_data_consents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiDataConsent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String consentVersion;

    @Column(nullable = false)
    private OffsetDateTime agreedAt;

    private AiDataConsent(User user, String consentVersion) {
        this.user = user;
        this.consentVersion = consentVersion;
        this.agreedAt = OffsetDateTime.now();
    }

    public static AiDataConsent agree(User user, String consentVersion) {
        return new AiDataConsent(user, consentVersion);
    }
}
