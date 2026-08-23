package com.github.galpiii.galpi.domain.collection.repository;

import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PullRequestRepository extends JpaRepository<PullRequest, Long> {

    Optional<PullRequest> findByRepositoryIdAndNumber(Long repositoryId, Integer number);

    List<PullRequest> findAllByRepositoryIdAndNumberIn(Long repositoryId,
                                                       Collection<Integer> numbers);

    /**
     * 분석 대상 PR. {@code pr_exclusions}에 있는 것은 수집은 유지하되 여기서 빠진다.
     *
     * <p>제외 여부를 애플리케이션에서 거르지 않고 쿼리로 빼는 이유는, 빼먹기 쉬운 조건이기
     * 때문이다. 사용자가 뺀 PR이 분석 근거로 다시 등장하면 제외 기능 자체가 신뢰를 잃는다.
     */
    @Query("""
            select pullRequest from PullRequest pullRequest
            where pullRequest.repository.id = :repositoryId
              and not exists (
                  select 1 from PrExclusion exclusion
                  where exclusion.pullRequest = pullRequest
              )
            order by pullRequest.mergedAt desc
            """)
    List<PullRequest> findAnalyzableByRepositoryId(@Param("repositoryId") Long repositoryId);
}
