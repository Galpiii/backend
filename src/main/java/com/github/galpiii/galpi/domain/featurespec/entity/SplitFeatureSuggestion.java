package com.github.galpiii.galpi.domain.featurespec.entity;

import com.github.galpiii.galpi.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 하나로 묶인 기능을 여러 기능으로 나누자는 제안 하나.
 *
 * <p>아직 실제 {@link Feature}가 아니다. 사용자가 분리를 적용하기 전의 제안일 뿐이라
 * {@code features}에 행을 만들지 않는다.
 *
 * <p>제안들을 묶는 부모 엔티티는 두지 않는다. 기능 하나당 분리안이 하나뿐이고 분리안 자체에
 * 딸린 정보도 없어서, 지금 만들면 행 수만 늘리고 아무것도 표현하지 못한다.
 *
 * <p>{@code suggestedSection}이 FK가 아닌 이유는 {@link DuplicateCandidate}와 같다.
 */
@Getter
@Entity
@Table(name = "split_feature_suggestions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SplitFeatureSuggestion extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feature_id", nullable = false)
    private Feature feature;

    @Column(nullable = false, length = 255)
    private String suggestedName;

    @Column(nullable = false, length = 255)
    private String suggestedSection;

    @Column(nullable = false)
    private int displayOrder;

    @Builder
    private SplitFeatureSuggestion(Feature feature, String suggestedName, String suggestedSection,
                                   int displayOrder) {
        this.feature = feature;
        this.suggestedName = suggestedName;
        this.suggestedSection = suggestedSection;
        this.displayOrder = displayOrder;
    }
}
