package com.github.galpiii.galpi.domain.user.entity;

import com.github.galpiii.galpi.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Getter
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Column(nullable = false, unique = true)
    private Long githubId;

    @Column(nullable = false, length = 39)
    private String login;

    @Column(length = 255)
    private String name;

    @Column(length = 255)
    private String email;

    @Column(length = 500)
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GithubConnectionStatus githubConnectionStatus;

    @Column(nullable = false)
    private OffsetDateTime connectedAt;

    private User(Long githubId, String login, String name, String email, String avatarUrl) {
        this.githubId = githubId;
        this.login = login;
        this.name = name;
        this.email = email;
        this.avatarUrl = avatarUrl;
        this.role = Role.USER;
        this.githubConnectionStatus = GithubConnectionStatus.CONNECTED;
        this.connectedAt = OffsetDateTime.now();
    }

    public static User ofGithub(Long githubId, String login, String name, String email, String avatarUrl) {
        return new User(githubId, login, name, email, avatarUrl);
    }

    public void syncGithubProfile(String login, String name, String email, String avatarUrl) {
        this.login = login;
        this.name = name;
        this.avatarUrl = avatarUrl;
        if (email != null) {
            this.email = email;
        }
        if (this.githubConnectionStatus != GithubConnectionStatus.CONNECTED) {
            this.githubConnectionStatus = GithubConnectionStatus.CONNECTED;
        }
        this.connectedAt = OffsetDateTime.now();
    }

    public void disconnectGithub() {
        this.githubConnectionStatus = GithubConnectionStatus.DISCONNECTED;
    }
}
