package com.github.galpiii.galpi.domain.collection.repository;

import com.github.galpiii.galpi.domain.collection.entity.PullRequestFile;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PullRequestFileRepository extends JpaRepository<PullRequestFile, Long> {

    List<PullRequestFile> findAllByPullRequestId(Long pullRequestId);

    /**
     * 상세 응답에 실을 만큼만 읽는다.
     *
     * <p>PR 하나의 변경 파일은 GitHub 상한인 3,000개까지 갈 수 있다. 전부 읽어 놓고 앞쪽
     * 몇백 개만 쓰는 것은 그 자체가 낭비라, 상한을 쿼리로 내린다.
     *
     * <p>id 순서가 곧 GitHub이 준 순서다. 쓰기 경로가 매번 지우고 다시 넣으므로 그 순서가
     * 보존되고, 경로 사전순보다 사람이 읽기에 낫다.
     */
    List<PullRequestFile> findAllByPullRequestId(Long pullRequestId, Pageable pageable);

    /**
     * 다시 수집할 때 기존 행을 비우고 새로 넣는다.
     *
     * <p>경로 단위로 대조해 갱신하지 않는 이유는, 지난번에 있던 파일이 이번에 사라진 경우를
     * 놓치기 때문이다. PR 하나의 변경 파일은 많아야 수천 건이라 지우고 다시 넣어도 된다.
     */
    void deleteAllByPullRequestId(Long pullRequestId);
}
