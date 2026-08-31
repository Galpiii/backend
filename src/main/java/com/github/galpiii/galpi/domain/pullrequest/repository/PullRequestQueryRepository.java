package com.github.galpiii.galpi.domain.pullrequest.repository;

import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestListRow;
import com.github.galpiii.galpi.domain.pullrequest.dto.RepositoryPullRequestCountRow;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * PR 조회 전용 저장소.
 *
 * <p>{@code PullRequestRepository}가 이미 {@code domain.collection}에 있는데 따로 두는 이유는
 * 의존 방향이다. 저쪽에 이 도메인의 행 타입을 반환하는 메서드를 더하면 collection이
 * pullrequest를 알게 되고, 인계 포트를 만들며 세워 둔 방향이 뒤집힌다. Spring Data는 같은
 * 엔티티에 저장소 인터페이스를 여럿 두는 것을 허용한다.
 *
 * <p><b>선택형 필터를 널 파라미터로 받지 않는다.</b> {@code :param is null} 형태는 널의 타입
 * 추론이 방언과 버전에 따라 달라 조용히 어긋난다. 대신 "필터 없음"을 값으로 표현한다 --
 * 빈 문자열, 전체를 담은 컬렉션, 불리언 플래그. 판정은 전부 서비스에서 하고 여기 오는 값은
 * 항상 널이 아니다.
 */
public interface PullRequestQueryRepository extends Repository<PullRequest, Long> {

    /**
     * 목록 한 페이지.
     *
     * <p>저장소·작성자·요약을 항목마다 다시 읽으면 그대로 N+1이 된다. {@code PullRequest}의
     * 연관이 전부 {@code LAZY}라 더 그렇다. 이 한 쿼리 안에서 조인으로 전부 읽고
     * {@link PullRequestListRow}에 담는다.
     *
     * <p>{@code summary} 본문은 담지 않는다. 목록이 쓰지 않고, 수십 건 x 300자를 필터를 바꿀
     * 때마다 실어 나를 이유가 없다.
     *
     * @param repositoryIds 프로젝트에 지금 연결된 저장소 id. 소유권 검증을 이미 통과한 값이라
     *                      이 조건 하나가 프로젝트 경계 역할을 한다. 비어 있으면 호출하지 않는다
     * @param authorLogin   소문자 login 정확 일치. 빈 문자열이면 필터 없음 -- GitHub login은
     *                      빈 문자열일 수 없어 센티널로 안전하다
     * @param query         빈 문자열이면 검색 없음
     * @param queryLike     {@code %검색어%}를 소문자로 만든 값
     * @param numericQuery  검색어가 숫자인지. 숫자면 PR 번호 정확 일치 또는 제목 부분 일치를
     *                      보고 작성자는 보지 않는다 -- "12"로 찾을 때 dev12가 섞이면 번호로
     *                      찾은 것인지 사람으로 찾은 것인지 알 수 없다
     * @param queryNumber   숫자 검색어의 값. 숫자가 아니면 {@code -1}이다. PR 번호는 양수라
     *                      어떤 행과도 맞지 않는다
     * @param statusFiltered {@code false}면 상태 조건 전체를 건너뛴다. 인계 전이거나 제외된
     *                      PR은 요약 행 자체가 없어서, 조건을 항상 걸면 그런 PR이 목록에서
     *                      통째로 사라진다
     */
    @Query(value = """
            select new com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestListRow(
                       pullRequest.id,
                       pullRequest.number,
                       pullRequest.title,
                       pullRequest.mergedAt,
                       pullRequest.htmlUrl,
                       pullRequest.dataCompleteness,
                       contributor.login,
                       contributor.avatarUrl,
                       repository.id,
                       repository.fullName,
                       analysis.status,
                       analysis.changeType,
                       analysis.analyzedAt,
                       analysis.errorCode,
                       (select count(exclusion.id) from PrExclusion exclusion
                         where exclusion.pullRequest.id = pullRequest.id))
              from PullRequest pullRequest
              join pullRequest.repository repository
              left join pullRequest.contributor contributor
              left join PullRequestAnalysis analysis
                     on analysis.pullRequest.id = pullRequest.id
             where repository.id in :repositoryIds
               and (:authorLogin = '' or lower(contributor.login) = :authorLogin)
               and (:statusFiltered = false or analysis.status in :statuses)
               and (:query = ''
                    or (:numericQuery = true
                        and (pullRequest.number = :queryNumber
                             or lower(pullRequest.title) like :queryLike))
                    or (:numericQuery = false
                        and (lower(pullRequest.title) like :queryLike
                             or lower(contributor.login) like :queryLike)))
            """,
            countQuery = """
            select count(pullRequest.id)
              from PullRequest pullRequest
              join pullRequest.repository repository
              left join pullRequest.contributor contributor
              left join PullRequestAnalysis analysis
                     on analysis.pullRequest.id = pullRequest.id
             where repository.id in :repositoryIds
               and (:authorLogin = '' or lower(contributor.login) = :authorLogin)
               and (:statusFiltered = false or analysis.status in :statuses)
               and (:query = ''
                    or (:numericQuery = true
                        and (pullRequest.number = :queryNumber
                             or lower(pullRequest.title) like :queryLike))
                    or (:numericQuery = false
                        and (lower(pullRequest.title) like :queryLike
                             or lower(contributor.login) like :queryLike)))
            """)
    Page<PullRequestListRow> findListRows(
            @Param("repositoryIds") Collection<Long> repositoryIds,
            @Param("authorLogin") String authorLogin,
            @Param("statusFiltered") boolean statusFiltered,
            @Param("statuses") Collection<PullRequestAnalysisStatus> statuses,
            @Param("query") String query,
            @Param("queryLike") String queryLike,
            @Param("numericQuery") boolean numericQuery,
            @Param("queryNumber") int queryNumber,
            Pageable pageable);

    /**
     * 헤더 집계. 저장소마다 한 행이고, 프로젝트 전체 숫자는 이 행들을 더해서 만든다.
     *
     * <p>저장소를 돌며 count를 세지 않는다. 저장소가 늘어나는 만큼 쿼리가 늘어나는 구조는
     * 목록보다 헤더에서 더 나쁘다 -- 헤더는 화면을 열 때마다 무조건 한 번씩 불린다.
     *
     * <p>{@code repositories}에서 시작하는 왼쪽 조인이다. {@code pull_requests}에서 시작하면
     * PR이 아직 하나도 없는 저장소가 목록에서 사라져, 방금 연결한 저장소가 화면에 안 보인다.
     */
    @Query("""
            select new com.github.galpiii.galpi.domain.pullrequest.dto.RepositoryPullRequestCountRow(
                       repository.id,
                       repository.fullName,
                       count(pullRequest.id),
                       sum(case when analysis.status = :failed then 1L else 0L end),
                       sum(case when analysis.status in :inFlight then 1L else 0L end),
                       sum(case when exclusion.id is not null then 1L else 0L end),
                       max(analysis.analyzedAt))
              from GithubRepository repository
              left join PullRequest pullRequest
                     on pullRequest.repository.id = repository.id
              left join PullRequestAnalysis analysis
                     on analysis.pullRequest.id = pullRequest.id
              left join PrExclusion exclusion
                     on exclusion.pullRequest.id = pullRequest.id
             where repository.project.id = :projectId
               and repository.unlinkedAt is null
             group by repository.id, repository.fullName
             order by repository.fullName
            """)
    List<RepositoryPullRequestCountRow> countByRepository(
            @Param("projectId") Long projectId,
            @Param("failed") PullRequestAnalysisStatus failed,
            @Param("inFlight") Collection<PullRequestAnalysisStatus> inFlight);

    /**
     * 상세 대상 PR. 소유권 검증을 조회 조건에 넣는다.
     *
     * <p>경로에 {@code projectId}가 없어도 되는 이유가 여기 있다. {@code pullRequestId}는
     * 전역 고유하고, 소유권은 {@code pull_requests -> repositories -> projects.owner_id}로
     * 확인한다. 남의 PR·삭제된 프로젝트의 PR·연결을 끊은 저장소의 PR은 모두 빈 값이 되어
     * 호출부에서 404가 된다 -- 403으로 구분하면 id를 훑어 남의 PR 존재 여부를 알 수 있다.
     */
    @Query("""
            select pullRequest from PullRequest pullRequest
              join fetch pullRequest.repository repository
              left join fetch pullRequest.contributor
             where pullRequest.id = :pullRequestId
               and repository.unlinkedAt is null
               and repository.project.owner.id = :ownerId
               and repository.project.deletedAt is null
            """)
    Optional<PullRequest> findDetail(@Param("pullRequestId") Long pullRequestId,
                                     @Param("ownerId") Long ownerId);
}
