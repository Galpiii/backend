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
}
