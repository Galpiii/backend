package com.github.galpiii.galpi.domain.analysis.repository;

import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AnalysisRunTargetRepository extends JpaRepository<AnalysisRunTarget, Long> {

    @Query("""
            select target from AnalysisRunTarget target
            join fetch target.repository repository
            join fetch target.analysisRun
            where repository.project.id = :projectId and repository.unlinkedAt is null
              and not exists (
                select newer.id from AnalysisRunTarget newer
                where newer.repository.id = repository.id
                  and newer.analysisRun.id > target.analysisRun.id
              )
            order by repository.id
            """)
    List<AnalysisRunTarget> findLatestForProject(@Param("projectId") Long projectId);

    List<AnalysisRunTarget> findAllByAnalysisRunId(Long analysisRunId);

    /**
     * 워커가 처리할 대상을 저장소까지 함께 읽는다.
     *
     * <p>지연 로딩으로 두면 저장소마다 추가 쿼리가 나가고, 워커는 트랜잭션 밖에서 오래 도는
     * 코드라 그 시점에 세션이 없다.
     */
    @Query("""
            select target from AnalysisRunTarget target
            join fetch target.repository
            where target.analysisRun.id = :analysisRunId
            order by target.id
            """)
    List<AnalysisRunTarget> findAllWithRepositoryByAnalysisRunId(
            @Param("analysisRunId") Long analysisRunId);
}
