package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.dto.GithubUserResponse;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 회원 행을 확보하는 짧은 트랜잭션.
 *
 * <p>여기서 하는 일은 "행이 있게 만드는 것"뿐이다. 프로필 갱신도 연결 확정도 하지 않는다 —
 * 그 둘은 토큰 저장과 함께 하나의 트랜잭션으로 묶여야 하는데({@link GithubConnectWriter}),
 * 행 생성만은 그 트랜잭션에 넣을 수 없다. 첫 로그인이 동시에 둘 들어오면 유니크 충돌이 나고,
 * 충돌이 난 트랜잭션 안에서는 기존 행을 다시 읽을 수 없기 때문이다. 그래서 이 부분만
 * {@code REQUIRES_NEW}로 떼어 두고 호출자가 충돌을 잡아 재조회한다.
 */
@Component
@RequiredArgsConstructor
class GithubUserWriter {

    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    User findOrCreate(GithubUserResponse githubUser) {
        return find(githubUser).orElseGet(() -> userRepository.saveAndFlush(User.ofGithub(
                githubUser.id(),
                githubUser.login(),
                githubUser.name(),
                githubUser.email(),
                githubUser.avatarUrl())));
    }

    /** 유니크 충돌 뒤 재조회용. github_id가 정본이라 login이 바뀌어도 같은 회원을 찾는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    Optional<User> find(GithubUserResponse githubUser) {
        return userRepository.findByGithubId(githubUser.id());
    }
}
