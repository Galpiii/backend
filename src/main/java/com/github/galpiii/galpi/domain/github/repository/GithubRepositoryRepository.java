package com.github.galpiii.galpi.domain.github.repository;

import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GithubRepositoryRepository extends JpaRepository<GithubRepository, Long> {

    List<GithubRepository> findAllByProjectId(Long projectId);

    @Query("select repository.githubRepositoryId from GithubRepository repository "
            + "where repository.project.id = :projectId")
    List<Long> findGithubRepositoryIdsByProjectId(@Param("projectId") Long projectId);

    Optional<GithubRepository> findByIdAndProjectId(Long id, Long projectId);

    List<GithubRepository> findAllByProjectIdAndGithubRepositoryIdIn(
            Long projectId, Collection<Long> githubRepositoryIds);

    /**
     * 이 사용자가 살아 있는 프로젝트에 연결해 둔 저장소 전부.
     *
     * <p>재연결 후 접근 상태를 다시 맞추는 데 쓴다. 삭제한 프로젝트의 저장소까지 GitHub에
     * 물으면 화면에 보이지도 않는 것 때문에 API 호출만 늘어난다.
     */
    @Query("select repository from GithubRepository repository "
            + "join repository.project project "
            + "where project.owner.id = :ownerId and project.deletedAt is null")
    List<GithubRepository> findAllByProjectOwnerId(@Param("ownerId") Long ownerId);
}
