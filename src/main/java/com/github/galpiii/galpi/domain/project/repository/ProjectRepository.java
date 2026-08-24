package com.github.galpiii.galpi.domain.project.repository;

import com.github.galpiii.galpi.domain.project.dto.ProjectSummaryRow;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.entity.ProjectStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /**
     * 소유자 확인과 삭제 여부를 한 번에 본다.
     *
     * <p>남의 프로젝트와 삭제된 프로젝트는 호출하는 쪽에서 모두 404가 된다. 403으로 구분하면
     * 프로젝트 id의 존재 여부가 새어 나가고, 삭제된 프로젝트의 하위 리소스만 살아 있으면
     * 삭제가 반쪽이 된다.
     */
    Optional<Project> findByIdAndOwnerIdAndDeletedAtIsNull(Long id, Long ownerId);

    long countByOwnerIdAndDeletedAtIsNull(Long ownerId);

    /**
     * 목록 한 페이지.
     *
     * <p>저장소 개수와 최근 분석 상태를 목록 한 건씩 다시 조회하면 그대로 N+1이 된다.
     * 개수는 상관 서브쿼리로, 최근 분석은 {@code last_analysis_run_id} 조인으로 이 쿼리
     * 안에서 함께 읽는다.
     */
    @Query(value = """
            select new com.github.galpiii.galpi.domain.project.dto.ProjectSummaryRow(
                       project.id,
                       project.name,
                       project.status,
                       project.onboardingStep,
                       (select count(repository.id) from GithubRepository repository
                         where repository.project.id = project.id
                           and repository.unlinkedAt is null),
                       project.activeSpecDocumentId,
                       run.id,
                       run.status,
                       run.createdAt,
                       run.finishedAt,
                       project.updatedAt)
              from Project project
              left join AnalysisRun run on run.id = project.lastAnalysisRunId
             where project.owner.id = :ownerId
               and project.deletedAt is null
               and project.status in :statuses
            """,
            countQuery = """
            select count(project.id)
              from Project project
             where project.owner.id = :ownerId
               and project.deletedAt is null
               and project.status in :statuses
            """)
    Page<ProjectSummaryRow> findSummaries(@Param("ownerId") Long ownerId,
                                          @Param("statuses") Collection<ProjectStatus> statuses,
                                          Pageable pageable);
}
