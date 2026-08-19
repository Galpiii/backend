package com.github.galpiii.galpi.domain.collection.repository;

import com.github.galpiii.galpi.domain.collection.entity.Contributor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContributorRepository extends JpaRepository<Contributor, Long> {

    Optional<Contributor> findByProjectIdAndGithubUserId(Long projectId, Long githubUserId);
}
