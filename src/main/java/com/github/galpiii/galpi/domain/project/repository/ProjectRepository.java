package com.github.galpiii.galpi.domain.project.repository;

import com.github.galpiii.galpi.domain.project.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {
}
