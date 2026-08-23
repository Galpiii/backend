package com.github.galpiii.galpi.domain.github.entity;

import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 프로젝트에 연결된 GitHub 저장소. 테이블은 {@code repositories}다.
 *
 * <p>클래스 이름에 Github를 붙인 것은 Spring Data의 리포지토리와 이름이 겹치는 것을 피하기
 * 위해서다. 저장하는 대상은 ERD의 {@code repositories} 그대로다.
 *
 * <p>{@code githubRepositoryId}만 관계의 키로 쓴다. owner·name·fullName은 표시용 스냅샷이라
 * 이름 변경 한 번에 달라지고, installationId는 캐시라 설치를 지웠다 다시 깔면 새 값이 된다.
 */
@Entity
@Getter
@Table(
        name = "repositories",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_repositories_project_github_repository",
                columnNames = {"project_id", "github_repository_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GithubRepository extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false)
    private Long githubRepositoryId;

    private Long installationId;

    @Column(nullable = false, length = 100)
    private String owner;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 200)
    private String fullName;

    @Column(name = "private", nullable = false)
    private boolean isPrivate;

    @Column(nullable = false, length = 255)
    private String defaultBranch;

    @Column(nullable = false, length = 500)
    private String htmlUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RepositoryAccessStatus accessStatus;

    private OffsetDateTime lastSyncedAt;

    /**
     * 연결을 끊은 시각. {@code null}이면 이 프로젝트에 연결돼 있다.
     *
     * <p>물리 삭제하지 않는 이유는 {@code analysis_run_repositories}와 {@code pull_requests}가
     * 이 행을 {@code ON DELETE CASCADE}로 물고 있어서다. 행을 지우면 저장소 하나를 빼는 것만으로
     * 그 저장소의 수집 근거와 과거 분석 결과가 함께 사라진다.
     */
    private OffsetDateTime unlinkedAt;

    private GithubRepository(Project project, RepositorySnapshot snapshot) {
        this.project = project;
        this.githubRepositoryId = snapshot.githubRepositoryId();
        this.accessStatus = RepositoryAccessStatus.ACCESSIBLE;
        applySnapshot(snapshot);
    }

    public static GithubRepository link(Project project, RepositorySnapshot snapshot) {
        return new GithubRepository(project, snapshot);
    }

    /**
     * 목록을 다시 조회했을 때 달라진 값을 반영한다.
     *
     * <p>다시 보였다는 것 자체가 접근 권한이 살아 있다는 뜻이므로 접근 상태도 되돌린다.
     */
    public void refresh(RepositorySnapshot snapshot) {
        applySnapshot(snapshot);
        this.accessStatus = RepositoryAccessStatus.ACCESSIBLE;
    }

    private void applySnapshot(RepositorySnapshot snapshot) {
        this.installationId = snapshot.installationId();
        this.owner = snapshot.owner();
        this.name = snapshot.name();
        this.fullName = snapshot.fullName();
        this.isPrivate = snapshot.isPrivate();
        this.defaultBranch = snapshot.defaultBranch();
        this.htmlUrl = snapshot.htmlUrl();
        this.lastSyncedAt = OffsetDateTime.now();
    }

    public void markInaccessible() {
        this.accessStatus = RepositoryAccessStatus.INACCESSIBLE;
    }

    /**
     * 프로젝트에서 뺀다. 행은 남는다.
     *
     * <p>이미 끊긴 저장소를 다시 끊어도 시각을 덮지 않는다. 처음 뺀 때가 이력이고, 두 번째
     * 호출은 아무것도 바꾸지 않는 요청이다.
     */
    public void unlink() {
        if (unlinkedAt == null) {
            this.unlinkedAt = OffsetDateTime.now();
        }
    }

    /**
     * 다시 연결한다. 끊겨 있던 행을 되살리고 최신 스냅샷을 반영한다.
     *
     * <p>새 행을 만들지 않는 덕분에 끊기 전에 수집해 둔 PR과 분석 이력이 그대로 이어진다.
     * 연결돼 있는 저장소에 불러도 {@link #refresh}와 같다.
     */
    public void relink(RepositorySnapshot snapshot) {
        this.unlinkedAt = null;
        refresh(snapshot);
    }

    public boolean isUnlinked() {
        return unlinkedAt != null;
    }
}
