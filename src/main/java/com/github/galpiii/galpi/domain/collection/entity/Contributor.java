package com.github.galpiii.galpi.domain.collection.entity;

import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * PR 작성자. 갈피 회원이 아닐 수 있어 {@code users}와 별도 테이블이다.
 *
 * <p>{@code users}와 마찬가지로 {@code githubUserId}가 유일한 영속 키다. {@code login}은
 * 바뀌고 버려진 login은 다른 사람이 가져갈 수 있어 표시용으로만 쓴다.
 */
@Entity
@Getter
@Table(
        name = "contributors",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_contributors_project_github_user",
                columnNames = {"project_id", "github_user_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Contributor extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false)
    private Long githubUserId;

    @Column(nullable = false, length = 39)
    private String login;

    @Column(length = 500)
    private String avatarUrl;

    private Contributor(Project project, Long githubUserId, String login, String avatarUrl) {
        this.project = project;
        this.githubUserId = githubUserId;
        this.login = login;
        this.avatarUrl = avatarUrl;
    }

    public static Contributor of(Project project, Long githubUserId, String login,
                                 String avatarUrl) {
        return new Contributor(project, githubUserId, login, avatarUrl);
    }

    /** 같은 GitHub 계정을 다시 봤을 때 표시용 값만 갱신한다. */
    public void refresh(String login, String avatarUrl) {
        if (login != null && !login.isBlank()) {
            this.login = login;
        }
        this.avatarUrl = avatarUrl;
    }
}
