package com.github.galpiii.galpi.domain.user.entity;

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

@Entity
@Getter
@Table(
        name = "user_oauth_tokens",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_oauth_tokens_user_provider",
                columnNames = {"user_id", "provider"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserOAuthToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OAuthProvider provider;

    @Column(nullable = false, columnDefinition = "text")
    private String encryptedAccessToken;

    private OffsetDateTime accessTokenExpiresAt;

    @Column(nullable = false)
    private int tokenVersion;

    @Column(nullable = false)
    private OffsetDateTime issuedAt;

    private UserOAuthToken(User user,
                           OAuthProvider provider,
                           String encryptedAccessToken,
                           OffsetDateTime accessTokenExpiresAt,
                           int tokenVersion) {
        this.user = user;
        this.provider = provider;
        this.encryptedAccessToken = encryptedAccessToken;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.tokenVersion = tokenVersion;
        this.issuedAt = OffsetDateTime.now();
    }

    public static UserOAuthToken issue(User user,
                                       OAuthProvider provider,
                                       String encryptedAccessToken,
                                       OffsetDateTime accessTokenExpiresAt,
                                       int tokenVersion) {
        return new UserOAuthToken(user, provider, encryptedAccessToken, accessTokenExpiresAt, tokenVersion);
    }

    public void replace(String encryptedAccessToken, OffsetDateTime accessTokenExpiresAt, int tokenVersion) {
        this.encryptedAccessToken = encryptedAccessToken;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.tokenVersion = tokenVersion;
        this.issuedAt = OffsetDateTime.now();
    }

    public boolean isExpired() {
        return accessTokenExpiresAt != null && !accessTokenExpiresAt.isAfter(OffsetDateTime.now());
    }
}
