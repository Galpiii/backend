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
}
