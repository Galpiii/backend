package com.github.galpiii.galpi.domain.user.repository;

import com.github.galpiii.galpi.domain.user.entity.OAuthProvider;
import com.github.galpiii.galpi.domain.user.entity.UserOAuthToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserOAuthTokenRepository extends JpaRepository<UserOAuthToken, Long> {

    Optional<UserOAuthToken> findByUserIdAndProvider(Long userId, OAuthProvider provider);

    void deleteByUserIdAndProvider(Long userId, OAuthProvider provider);
}
