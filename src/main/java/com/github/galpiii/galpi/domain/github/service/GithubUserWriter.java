package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.dto.GithubUserResponse;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
@RequiredArgsConstructor
class GithubUserWriter {

    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    User updateExistingOrCreate(GithubUserResponse githubUser) {
        return findAndSync(githubUser)
                .orElseGet(() -> userRepository.saveAndFlush(User.ofGithub(
                        githubUser.id(),
                        githubUser.login(),
                        githubUser.name(),
                        githubUser.email(),
                        githubUser.avatarUrl())));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Optional<User> updateExisting(GithubUserResponse githubUser) {
        return findAndSync(githubUser);
    }

    private Optional<User> findAndSync(GithubUserResponse githubUser) {
        return userRepository.findByGithubId(githubUser.id())
                .map(user -> {
                    user.syncGithubProfile(
                            githubUser.login(),
                            githubUser.name(),
                            githubUser.email(),
                            githubUser.avatarUrl());
                    return user;
                });
    }
}
