package com.github.galpiii.galpi.domain.github.repository;

import com.github.galpiii.galpi.domain.github.entity.GithubTokenRevocation;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface GithubTokenRevocationRepository extends JpaRepository<GithubTokenRevocation, Long> {

    List<GithubTokenRevocation> findByAttemptsLessThanAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            int maxAttempts, OffsetDateTime dueAt, Limit limit);

    long countByAttemptsGreaterThanEqual(int maxAttempts);
}
