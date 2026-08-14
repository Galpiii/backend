package com.github.galpiii.galpi.domain.featurespec.repository;

import com.github.galpiii.galpi.domain.featurespec.entity.DuplicateCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DuplicateCandidateRepository extends JpaRepository<DuplicateCandidate, Long> {
}
