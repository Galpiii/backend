package com.github.galpiii.galpi.domain.collection.repository;

import com.github.galpiii.galpi.domain.collection.entity.PrExclusion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PrExclusionRepository extends JpaRepository<PrExclusion, Long> {

    Optional<PrExclusion> findByPullRequestId(Long pullRequestId);

    /**
     * 제외된 PR의 번호.
     *
     * <p>내부 id가 아니라 번호로 받는 이유는 수집 중에 쓰기 때문이다. 수집기는 GitHub에서 온
     * PR 번호를 들고 있고, 그 PR을 저장하기 전에 분석 대상인지 판단해야 한다.
     */
    @Query("""
            select exclusion.pullRequest.number from PrExclusion exclusion
            where exclusion.pullRequest.repository.id = :repositoryId
            """)
    List<Integer> findExcludedNumbersByRepositoryId(@Param("repositoryId") Long repositoryId);
}
