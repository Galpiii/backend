package com.github.galpiii.galpi.domain.github.repository;

import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * <p>연결 해제는 소프트 삭제다({@code unlinked_at}). 그래서 이 인터페이스의 메서드는 두 부류로
 * 갈린다 — <b>프로젝트에 지금 무엇이 붙어 있는지</b> 묻는 것은 끊긴 행을 걸러 내고,
 * <b>이력이나 진행 중인 작업</b>을 다루는 것은 걸러 내지 않는다. 뒤쪽까지 걸러 버리면 이력을
 * 남기려고 소프트 삭제로 바꾼 의미가 없어진다.
 *
 * <p>그래서 엔티티에 {@code @SQLRestriction}을 걸지 않는다. 그건 전역이라 이력 경로까지 함께
 * 막는다. 조건은 메서드마다 명시한다.
 *
 * <p>{@code findById}·{@code findAllById}는 내부 id로 찾는 자리다. 워커와 PR 쓰기가 진행 중인
 * 작업의 대상을 다시 읽는 데 쓰므로 끊긴 행도 그대로 돌려준다.
 */
public interface GithubRepositoryRepository extends JpaRepository<GithubRepository, Long> {

    /** 프로젝트에 지금 연결된 저장소. 상세·목록·분석 대상이 모두 이것을 본다. */
    @Query("select repository from GithubRepository repository "
            + "where repository.project.id = :projectId "
            + "and repository.unlinkedAt is null")
    List<GithubRepository> findAllByProjectId(@Param("projectId") Long projectId);

    /** 선택 목록에 linked 배지를 다는 데 쓴다. 끊은 저장소는 다시 고를 수 있어야 한다. */
    @Query("select repository.githubRepositoryId from GithubRepository repository "
            + "where repository.project.id = :projectId "
            + "and repository.unlinkedAt is null")
    List<Long> findGithubRepositoryIdsByProjectId(@Param("projectId") Long projectId);

    /** 연결 해제 대상. 이미 끊긴 저장소는 찾지 못해 404가 된다. */
    @Query("select repository from GithubRepository repository "
            + "where repository.id = :id "
            + "and repository.project.id = :projectId "
            + "and repository.unlinkedAt is null")
    Optional<GithubRepository> findByIdAndProjectId(@Param("id") Long id,
                                                    @Param("projectId") Long projectId);

    /** URL로 찾은 저장소가 이미 이 프로젝트에 붙어 있는지. 끊긴 것은 붙어 있지 않은 것이다. */
    boolean existsByProjectIdAndGithubRepositoryIdAndUnlinkedAtIsNull(
            Long projectId, Long githubRepositoryId);

    /**
     * 연결할 때 이미 있는 행을 찾는다. <b>끊긴 행까지 함께 읽는다.</b>
     *
     * <p>여기서 걸러 내면 끊었던 저장소를 다시 고를 때 새 행을 만들게 되고, 그 순간
     * {@code uk_repositories_project_github_repository}에 걸려 500이 된다. 되살릴 대상을
     * 찾는 것이 이 조회의 목적이다.
     */
    @Query("select repository from GithubRepository repository "
            + "where repository.project.id = :projectId "
            + "and repository.githubRepositoryId in :githubRepositoryIds")
    List<GithubRepository> findAllForRelink(
            @Param("projectId") Long projectId,
            @Param("githubRepositoryIds") Collection<Long> githubRepositoryIds);

    /**
     * 이 사용자가 살아 있는 프로젝트에 지금 연결해 둔 저장소 전부.
     *
     * <p>재연결 후 접근 상태를 다시 맞추는 데 쓴다. 삭제한 프로젝트의 저장소까지 GitHub에
     * 물으면 화면에 보이지도 않는 것 때문에 API 호출만 늘어난다. 끊어 둔 저장소도 마찬가지다 —
     * 사용자가 이미 뺀 저장소를 "접근할 수 없다"고 되돌려 주면 안 된다.
     */
    @Query("select repository from GithubRepository repository "
            + "join repository.project project "
            + "where project.owner.id = :ownerId and project.deletedAt is null "
            + "and repository.unlinkedAt is null")
    List<GithubRepository> findAllByProjectOwnerId(@Param("ownerId") Long ownerId);
}
