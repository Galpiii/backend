package com.github.galpiii.galpi.domain.project.entity;

import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 분석의 단위. 저장소 여러 개와 기능명세서 하나를 묶는다.
 *
 * <p>{@code activeSpecDocumentId}와 {@code lastAnalysisRunId}는 연관이 아니라 id로 둔다.
 * 두 값 모두 "지금 무엇을 보고 있는지"를 가리키는 포인터일 뿐 소유 관계가 아니고, 연관으로
 * 만들면 {@code SpecDocument}·{@code AnalysisRun}이 이미 {@code Project}를 참조하고 있어
 * 엔티티가 서로를 물게 된다. 목록 조회는 이 id로 조인해 한 번에 읽는다.
 */
@Entity
@Getter
@Table(name = "projects")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project extends BaseEntity {

    public static final int MAX_NAME_LENGTH = 100;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectOnboardingStep onboardingStep;

    private Long activeSpecDocumentId;

    private Long lastAnalysisRunId;

    private OffsetDateTime deletedAt;

    private Project(User owner, String name) {
        this.owner = owner;
        this.name = name;
        this.status = ProjectStatus.DRAFT;
        this.onboardingStep = ProjectOnboardingStep.SPEC;
    }

    /**
     * 위저드 ① 단계에서 만들어진다. 명세서도 저장소도 없는 상태로 시작한다.
     *
     * <p>위저드 끝까지 모았다가 한 번에 만들지 않는 이유는 ② 단계다. 거기서 GitHub App
     * 설치 화면으로 나갔다 돌아오면 프론트 상태가 날아가는데, 서버에 projectId가 이미 있어야
     * 돌아올 자리가 생긴다.
     */
    public static Project create(User owner, String name) {
        return new Project(owner, name);
    }

    public void rename(String name) {
        this.name = name;
    }

    /** 허용 전이인지는 {@link ProjectStatus#allowsTransitionTo}가 판단한다. */
    public void changeStatus(ProjectStatus target) {
        this.status = target;
    }

    /** 뒤로 가는 갱신은 무시한다. 앞선 단계를 이미 지났다면 그대로 둔다. */
    public void advanceOnboardingStep(ProjectOnboardingStep target) {
        if (onboardingStep.isBefore(target)) {
            this.onboardingStep = target;
        }
    }

    /**
     * 저장소가 1개 이상이 됐다.
     *
     * <p>{@code ARCHIVED}는 건드리지 않는다. 보관한 프로젝트에 저장소를 붙였다고 보관이
     * 풀리면, 되돌리는 것은 사용자가 아니라 부수효과가 된다.
     */
    public void markRepositoriesLinked() {
        if (status == ProjectStatus.DRAFT) {
            this.status = ProjectStatus.ACTIVE;
        }
        advanceOnboardingStep(ProjectOnboardingStep.ANALYSIS);
    }

    /**
     * 활성 명세서를 교체한다. 프로젝트당 활성 문서는 하나다.
     *
     * <p>이전 문서 행은 지우지 않는다. 추출 결과가 매달려 있고, 새 업로드가 실패해도 과거
     * 문서를 잃지 않아야 한다.
     */
    public void attachSpecDocument(Long specDocumentId) {
        this.activeSpecDocumentId = specDocumentId;
        advanceOnboardingStep(ProjectOnboardingStep.REPOSITORIES);
    }

    public void markLastAnalysisRun(Long analysisRunId) {
        this.lastAnalysisRunId = analysisRunId;
    }

    /**
     * 물리 삭제하지 않는다. {@code repositories}·{@code analysis_runs}·{@code spec_documents}가
     * FK로 매달려 있어 연쇄 범위가 크고, 되돌릴 방법도 없다.
     */
    public void softDelete() {
        if (deletedAt == null) {
            this.deletedAt = OffsetDateTime.now();
        }
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
