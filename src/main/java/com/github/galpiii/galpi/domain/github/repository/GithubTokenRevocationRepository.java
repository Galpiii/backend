package com.github.galpiii.galpi.domain.github.repository;

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

    long countByStatus(GithubTokenRevocationStatus status);
}
