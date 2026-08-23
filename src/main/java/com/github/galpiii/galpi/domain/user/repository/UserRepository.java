package com.github.galpiii.galpi.domain.user.repository;

import com.github.galpiii.galpi.domain.user.entity.GithubConnectionStatus;
import com.github.galpiii.galpi.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByGithubId(Long githubId);

    boolean existsByIdAndGithubConnectionStatus(Long id, GithubConnectionStatus status);

    /**
     * 연결 상태를 바꾸거나 그 상태에 기대어 무언가를 만드는 트랜잭션이 잡는 행 잠금.
     *
     * <p>분석 작업 생성과 연결 해제가 겹치면, 생성 쪽이 "연결됨"을 읽은 뒤 해제 쪽이 취소를
     * 끝내고, 그 다음 생성이 커밋되는 순서가 가능하다. 그러면 근거가 사라진 뒤에도 실행
     * 가능한 작업이 남는다. 양쪽 모두 이 잠금을 <b>트랜잭션의 첫 구문</b>으로 잡아 그 순서
     * 자체를 없앤다 — 잠금 순서가 같아야 데드락도 생기지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
