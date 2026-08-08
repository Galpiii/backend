package com.github.galpiii.galpi.domain.auth.dto;

import com.github.galpiii.galpi.domain.user.entity.GithubConnectionStatus;
import com.github.galpiii.galpi.domain.user.entity.User;

public record MeResponse(
        Long userId,
        String login,
        String name,
        String email,
        String avatarUrl,
        Github github
) {
    public record Github(
            Long githubId,
            GithubConnectionStatus connectionStatus,
            boolean githubTokenValid
    ) {
    }

    public static MeResponse of(User user, boolean githubTokenValid) {
        return new MeResponse(
                user.getId(),
                user.getLogin(),
                user.getName(),
                user.getEmail(),
                user.getAvatarUrl(),
                new Github(user.getGithubId(), user.getGithubConnectionStatus(), githubTokenValid));
    }
}
