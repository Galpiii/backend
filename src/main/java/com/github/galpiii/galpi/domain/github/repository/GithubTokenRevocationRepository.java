package com.github.galpiii.galpi.domain.github.repository;

import com.github.galpiii.galpi.domain.github.entity.GithubRevocationType;
import com.github.galpiii.galpi.domain.github.entity.GithubTokenRevocation;
import com.github.galpiii.galpi.domain.github.entity.GithubTokenRevocationStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;

public interface GithubTokenRevocationRepository extends JpaRepository<GithubTokenRevocation, Long> {

    @Query("""
            select r.id from GithubTokenRevocation r
            where r.status = :status and r.nextAttemptAt <= :dueAt
            order by r.nextAttemptAt asc
            """)
    List<Long> findDueIds(GithubTokenRevocationStatus status, OffsetDateTime dueAt, Limit limit);

    /**
     * 이 사용자의 grant 폐기 대기 항목을 모두 지운다. 상태는 가리지 않는다 — 재승인을 받은
     * authorization을 폐기하려는 의도는 DEAD 행이라도 되살아나면 안 된다.
     */
    int deleteByUserIdAndRevocationType(Long userId, GithubRevocationType revocationType);

    long countByStatus(GithubTokenRevocationStatus status);
}
