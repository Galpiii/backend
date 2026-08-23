package com.github.galpiii.galpi.domain.collection.repository;

import com.github.galpiii.galpi.domain.collection.entity.PullRequestFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PullRequestFileRepository extends JpaRepository<PullRequestFile, Long> {

    List<PullRequestFile> findAllByPullRequestId(Long pullRequestId);

    /**
     * 다시 수집할 때 기존 행을 비우고 새로 넣는다.
     *
     * <p>경로 단위로 대조해 갱신하지 않는 이유는, 지난번에 있던 파일이 이번에 사라진 경우를
     * 놓치기 때문이다. PR 하나의 변경 파일은 많아야 수천 건이라 지우고 다시 넣어도 된다.
     */
    void deleteAllByPullRequestId(Long pullRequestId);
}
