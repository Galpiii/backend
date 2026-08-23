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

    /**
     * 마지막으로 연결이 확정된 시각. 토큰까지 저장된 뒤에만 찍힌다.
     *
     * <p>가입만 하고 연결이 끝나지 않은 상태가 있을 수 있어 null을 허용한다 — 연결된 적 없는
     * 회원에게 연결 시각을 지어내는 것보다 비어 있는 편이 정확하다.
     */
    private OffsetDateTime connectedAt;

    private User(Long githubId, String login, String name, String email, String avatarUrl) {
        this.githubId = githubId;
        this.login = login;
        this.name = name;
        this.email = email;
        this.avatarUrl = avatarUrl;
        this.role = Role.USER;
        // 행이 생겼다고 연결된 것은 아니다. 토큰까지 저장한 트랜잭션이 connectGithub()으로
        // 확정한다 — 그 사이에 실패하면 "연결됐는데 토큰이 없는" 회원이 남는다.
        this.githubConnectionStatus = GithubConnectionStatus.DISCONNECTED;
    }

    public static User ofGithub(Long githubId, String login, String name, String email, String avatarUrl) {
        return new User(githubId, login, name, email, avatarUrl);
    }

    /**
     * GitHub이 준 프로필 스냅샷을 반영한다. <b>연결 상태는 건드리지 않는다.</b>
     *
     * <p>프로필 갱신과 연결 확정을 한 메서드에 두면, 프로필만 새로고침하는 경로가 연결 상태를
     * 되살리거나, 해제와 겹쳤을 때 오래 읽은 상태로 덮어쓴다.
     */
    public void syncGithubProfile(String login, String name, String email, String avatarUrl) {
        this.login = login;
        this.name = name;
        this.avatarUrl = avatarUrl;
        if (email != null) {
            this.email = email;
        }
    }

    /**
     * 연결을 확정한다. 새 토큰을 저장한 <b>같은 트랜잭션</b>에서만 불러야 한다.
     *
     * <p>토큰 저장과 이 전이가 갈라지면 "CONNECTED인데 토큰이 없는" 또는 그 반대의 상태가
     * 남는다. 앞은 사용자가 아무것도 못 하고, 뒤는 끊긴 사용자의 토큰으로 GitHub을 계속
     * 부를 수 있다.
     */
    public void connectGithub() {
        this.githubConnectionStatus = GithubConnectionStatus.CONNECTED;
        this.connectedAt = OffsetDateTime.now();
    }

    public void disconnectGithub() {
        this.githubConnectionStatus = GithubConnectionStatus.DISCONNECTED;
    }
}
