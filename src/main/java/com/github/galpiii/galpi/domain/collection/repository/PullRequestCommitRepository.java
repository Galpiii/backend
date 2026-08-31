package com.github.galpiii.galpi.domain.collection.repository;

import com.github.galpiii.galpi.domain.collection.entity.PullRequestCommit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PullRequestCommitRepository extends JpaRepository<PullRequestCommit, Long> {

    List<PullRequestCommit> findAllByPullRequestId(Long pullRequestId);

    List<PullRequestCommit> findAllByPullRequestId(Long pullRequestId, Pageable pageable);

    void deleteAllByPullRequestId(Long pullRequestId);
}
