package com.github.galpiii.galpi.domain.featurematch.repository;

import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchCounts;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchFeatureRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchFileRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeaturePrMatchRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchPullRequestRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchProjectRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRepositoryRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRequirementLinkRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRequirementRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRunRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchTargetRow;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRun;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchTargetStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 목록과 검증에 필요한 값만 조인으로 읽는다. 엔티티 저장소와 분리해 N+1을 피한다.
 */
public interface FeatureMatchQueryRepository extends Repository<FeatureMatchRun, Long> {

    @Query("select count(target) from FeatureMatchTarget target where target.featureMatchRun.id = :runId")
    long targetCount(@Param("runId") long runId);

    @Query("""
            select run.id from FeatureMatchRun run
             where run.status in ('QUEUED', 'RUNNING')
               and not exists (select target.id from FeatureMatchTarget target
                   where target.featureMatchRun.id = run.id
                     and target.status in ('PENDING', 'RUNNING'))
             order by run.id
            """)
    List<Long> finalizableRunIds(Limit limit);

    @Query("""
            select new com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchPullRequestRow(
            pr.id, repository.id, repository.fullName, pr.number, pr.title, pr.body, pr.headSha,
            contributor.login, pr.mergedAt, pr.htmlUrl, pr.dataCompleteness,
            analysis.id, analysis.status, analysis.headSha, analysis.summary,
            analysis.changeType)
            from PullRequest pr join pr.repository repository left join pr.contributor contributor
            left join PullRequestAnalysis analysis on analysis.pullRequest.id = pr.id
            where analysis.id = :analysisId AND repository.project.id = :projectId and repository.unlinkedAt is null
            order by pr.mergedAt desc, pr.id desc
            """)
    FeatureMatchPullRequestRow pullRequestForAnalysis(@Param("analysisId") long analysisId,
                                                      @Param("projectId") long projectId);

    @Query("""
            select new com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchProjectRow(
            project.id, project.owner.id, project.activeSpecDocumentId)
            from Project project
            where project.id = :id and project.owner.id = :userId and project.deletedAt is null
            """)
    FeatureMatchProjectRow findProject(@Param("id") long id, @Param("userId") long userId);

    @Query("""
            select project.id from Feature feature
            join feature.specDocument document join document.project project
            where feature.id = :featureId and project.owner.id = :userId and project.deletedAt is null
            and project.activeSpecDocumentId = document.id
            """)
    Long featureProject(@Param("featureId") long featureId, @Param("userId") long userId);

    @Query("""
            select project.id from FeaturePrMatch match
            join match.feature feature join feature.specDocument document join document.project project
            where match.id = :matchId and project.owner.id = :userId and project.deletedAt is null
            and project.activeSpecDocumentId = document.id
            """)
    Long matchProject(@Param("matchId") long matchId, @Param("userId") long userId);

    @Query("""
            select count(document) > 0 from SpecDocument document
            where document.id = :id and document.extractionStatus = 'COMPLETED'
            """)
    boolean documentReady(@Param("id") long id);

    @Query("""
            select count(user) > 0 from User user
            where user.id = :userId and user.githubConnectionStatus = 'CONNECTED'
            """)
    boolean connected(@Param("userId") long userId);

    @Query("""
            select count(run) > 0 from AnalysisRun run
            where run.project.id = :projectId and run.status in ('QUEUED', 'RUNNING')
            """)
    boolean collectionBusy(@Param("projectId") long projectId);

    @Query("""
            select new com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRepositoryRow(
            repository.id, repository.fullName)
            from GithubRepository repository
            where repository.project.id = :projectId and repository.unlinkedAt is null order by repository.id
            """)
    List<FeatureMatchRepositoryRow> repositories(@Param("projectId") long projectId);

    @Query("""
            select new com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchFeatureRow(
            feature.id, section.id, section.title, section.displayOrder,
            feature.name, feature.displayOrder, feature.reviewStatus,
            feature.sourcePageStart, feature.sourcePageEnd)
            from Feature feature left join feature.section section
            where feature.specDocument.id = :documentId
            order by section.displayOrder nulls last, section.id, feature.displayOrder, feature.id
            """)
    List<FeatureMatchFeatureRow> features(@Param("documentId") long documentId);

    @Query("""
            select new com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRequirementRow(
            requirement.id, requirement.feature.id, requirement.content, requirement.displayOrder)
            from FeatureRequirement requirement where requirement.feature.specDocument.id = :documentId
            order by requirement.displayOrder, requirement.id
            """)
    List<FeatureMatchRequirementRow> requirements(@Param("documentId") long documentId);

    @Query("""
            select new com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchPullRequestRow(
            pr.id, repository.id, repository.fullName, pr.number, pr.title, pr.body, pr.headSha,
            contributor.login, pr.mergedAt, pr.htmlUrl, pr.dataCompleteness,
            analysis.id, analysis.status, analysis.headSha, analysis.summary,
            analysis.changeType)
            from PullRequest pr join pr.repository repository left join pr.contributor contributor
            left join PullRequestAnalysis analysis on analysis.pullRequest.id = pr.id
            where repository.project.id = :projectId and repository.unlinkedAt is null
            order by pr.mergedAt desc, pr.id desc
            """)
    List<FeatureMatchPullRequestRow> pullRequests(@Param("projectId") long projectId);

    @Query("""
            select new com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchPullRequestRow(
            pr.id, repository.id, repository.fullName, pr.number, pr.title, null, pr.headSha,
            contributor.login, pr.mergedAt, pr.htmlUrl, pr.dataCompleteness,
            analysis.id, analysis.status, analysis.headSha, analysis.summary, analysis.changeType)
            from PullRequest pr join pr.repository repository left join pr.contributor contributor
            join PullRequestAnalysis analysis on analysis.pullRequest.id = pr.id
            where repository.project.id = :projectId and repository.unlinkedAt is null
              and (:repositoryId is null or repository.id = :repositoryId)
              and exists (select target.id from FeatureMatchTarget target
                  where target.featureMatchRun.id = :runId
                    and target.pullRequestAnalysis.id = analysis.id and target.status = 'COMPLETED')
              and not exists (select match.id from FeaturePrMatch match
                  where match.pullRequest.id = pr.id and match.feature.specDocument.id = :documentId
                    and (match.source = 'USER' or match.featureMatchRun.id = :runId))
              and (:query = '' or cast(pr.number as string) = :query
                  or locate(:query, lower(pr.title)) > 0
                  or locate(:query, lower(contributor.login)) > 0)
            order by pr.mergedAt desc, pr.id desc
            """)
    Page<FeatureMatchPullRequestRow> unmatched(
            @Param("projectId") long projectId, @Param("runId") long runId,
            @Param("documentId") long documentId, @Param("repositoryId") Long repositoryId,
            @Param("query") String query, Pageable pageable);

    @Query("""
            select commit.message from PullRequestCommit commit
            where commit.pullRequest.id = :prId order by commit.id
            """)
    List<String> findCommits(@Param("prId") long prId, Limit limit);

    @Query("""
            select new com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchFileRow(
            file.path, cast(file.changeStatus as string), file.additions, file.deletions)
            from PullRequestFile file where file.pullRequest.id = :prId order by file.id
            """)
    List<FeatureMatchFileRow> findFiles(@Param("prId") long prId, Limit limit);

    @Query("""
            select new com.github.galpiii.galpi.domain.featurematch.dto.FeaturePrMatchRow(
            match.id, match.feature.id, match.pullRequest.id, match.source,
            match.reason, match.user.id, match.createdAt)
            from FeaturePrMatch match
            where match.feature.specDocument.id = :documentId and match.pullRequest.repository.unlinkedAt is null
            and (match.source = 'USER' or match.featureMatchRun.id = :runId)
            order by match.id
            """)
    List<FeaturePrMatchRow> matches(@Param("runId") long runId, @Param("documentId") long documentId);

    @Query("""
            select new com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRequirementLinkRow(
            link.featurePrMatch.id, link.featureRequirement.id)
            from FeaturePrMatchRequirement link where link.featurePrMatch.featureMatchRun.id = :runId
            """)
    List<FeatureMatchRequirementLinkRow> requirementLinks(@Param("runId") long runId);

    @Query("""
            select new com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRunRow(
            run.id, run.project.id, run.specDocument.id, run.user.id, run.status,
            run.featureSnapshotHash, run.featureCount, run.eligiblePrCount,
            run.excludedFailedPrCount, run.excludedCancelledPrCount, run.failureCode,
            run.startedAt, run.finishedAt, run.createdAt)
            from FeatureMatchRun run where run.id = :id
            """)
    FeatureMatchRunRow run(@Param("id") long id);

    @Query("""
            select new com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRunRow(
            run.id, run.project.id, run.specDocument.id, run.user.id, run.status,
            run.featureSnapshotHash, run.featureCount, run.eligiblePrCount,
            run.excludedFailedPrCount, run.excludedCancelledPrCount, run.failureCode,
            run.startedAt, run.finishedAt, run.createdAt)
            from FeatureMatchRun run where run.project.id = :projectId order by run.id desc
            """)
    List<FeatureMatchRunRow> findLatest(@Param("projectId") long projectId, Limit limit);

    @Query("""
            select new com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchTargetRow(
            target.id, target.featureMatchRun.id, target.pullRequestAnalysis.id,
            target.analysisHeadSha, target.analysisSnapshotHash, target.status, target.claimedBy,
            target.attempts)
            from FeatureMatchTarget target where target.id = :id
            """)
    FeatureMatchTargetRow target(@Param("id") long id);

    @Query("""
            select new com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchTargetRow(
            target.id, target.featureMatchRun.id, target.pullRequestAnalysis.id,
            target.analysisHeadSha, target.analysisSnapshotHash, target.status, target.claimedBy,
            target.attempts)
            from FeatureMatchTarget target where target.featureMatchRun.id = :runId order by target.id
            """)
    List<FeatureMatchTargetRow> targets(@Param("runId") long runId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select row.id from SpecDocument row where row.id = :id")
    Long lockDocument(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select row.id from FeatureMatchRun row where row.id = :id")
    Long lockRun(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select row.id from FeatureMatchTarget row where row.id = :id")
    Long lockTarget(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select row.id from PullRequestAnalysis row where row.id = :id")
    Long lockAnalysis(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select project.id from Project project
            where project.id = :id and project.owner.id = :userId and project.deletedAt is null
            """)
    Long lockProject(@Param("id") long id, @Param("userId") long userId);

    default FeatureMatchProjectRow project(long id, long userId, boolean forUpdate) {
        if (forUpdate) {
            lockProject(id, userId);
        }
        return findProject(id, userId);
    }

    default FeatureMatchRunRow latest(long projectId) {
        return findLatest(projectId, Limit.of(1)).stream().findFirst().orElse(null);
    }

    default List<String> commits(long prId) {
        return findCommits(prId, Limit.of(100));
    }

    default List<FeatureMatchFileRow> files(long prId) {
        return findFiles(prId, Limit.of(300));
    }

    @Query("""
            select target.status as status, count(target) as total
            from FeatureMatchTarget target
            where target.featureMatchRun.id = :runId group by target.status
            """)
    List<TargetStatusCount> countTargetsByStatus(@Param("runId") long runId);

    default FeatureMatchCounts counts(long runId) {
        int pending = 0;
        int running = 0;
        int completed = 0;
        int failed = 0;
        int cancelled = 0;
        for (TargetStatusCount row : countTargetsByStatus(runId)) {
            int count = Math.toIntExact(row.getTotal());
            switch (row.getStatus()) {
                case PENDING -> pending = count;
                case RUNNING -> running = count;
                case COMPLETED -> completed = count;
                case FAILED -> failed = count;
                case CANCELLED -> cancelled = count;
            }
        }
        return new FeatureMatchCounts(pending, running, completed, failed, cancelled);
    }

    interface TargetStatusCount {
        FeatureMatchTargetStatus getStatus();

        long getTotal();
    }
}
