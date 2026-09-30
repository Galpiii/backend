package com.github.galpiii.galpi.domain.featurematch.service;

import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchCounts;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRepositoryRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRequirementRow;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;

import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchFeatureRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeaturePrMatchRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchPullRequestRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRequirementLinkRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchTargetRow;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchDetailResponse;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchPullRequestResponse;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchResultsResponse;
import com.github.galpiii.galpi.domain.featurematch.dto.response.UnmatchedPullRequestListResponse;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureEvidenceStatus;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchFilter;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchSource;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchTargetStatus;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
import com.github.galpiii.galpi.domain.featurematch.service.FeatureMatchScope.View;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class FeatureMatchQueryService {

    private final FeatureMatchQueryRepository queryRepository;
    private final FeatureMatchScope scope;

    public FeatureMatchResultsResponse results(
            long projectId,
            long userId,
            Long repositoryId,
            String query,
            FeatureMatchFilter filter) {
        View view = scope.result(projectId, userId, false);
        scope.repositoryScope(view, repositoryId);
        List<FeaturePrMatchRow> scoped = scopeMatches(view, repositoryId);
        Set<Long> matched = matchedPrIds(view.matches());
        FeatureMatchResultsResponse.Summary summary = summary(view, matched);
        String normalizedQuery = normalize(query);
        FeatureMatchFilter actualFilter = filter == null ? FeatureMatchFilter.ALL : filter;
        Map<Long, List<FeatureMatchResultsResponse.Feature>> grouped = new LinkedHashMap<>();
        for (FeatureMatchFeatureRow feature : view.features()) {
            List<FeaturePrMatchRow> links = scoped.stream().filter(m -> m.featureId() == feature.id()).toList();
            boolean found = !links.isEmpty();
            if (!feature.name().toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                continue;
            }
            if (actualFilter == FeatureMatchFilter.EVIDENCE_FOUND && !found) {
                continue;
            }
            if (actualFilter == FeatureMatchFilter.ATTENTION_REQUIRED && found && !unreviewed(feature)) {
                continue;
            }
            long requirementCount = view.requirements().stream()
                    .filter(requirement -> requirement.featureId() == feature.id()).count();
            long manualCount = links.stream().filter(match -> match.source() == FeatureMatchSource.USER).count();
            FeatureMatchResultsResponse.Feature item = new FeatureMatchResultsResponse.Feature(
                    feature.id(), feature.name(), feature.reviewStatus(), evidence(found),
                    feature.sourcePageStart(), feature.sourcePageEnd(), requirementCount,
                    matchedPrIds(links).size(), manualCount);
            grouped.computeIfAbsent(feature.sectionId(), ignored -> new ArrayList<>()).add(item);
        }
        List<FeatureMatchResultsResponse.Section> sections = grouped.entrySet().stream().map(e -> {
            FeatureMatchFeatureRow first = view.features().stream()
                    .filter(feature -> Objects.equals(feature.sectionId(), e.getKey())).findFirst().orElseThrow();
            return new FeatureMatchResultsResponse.Section(
                    e.getKey(), first.sectionTitle(), first.sectionOrder(), e.getValue());
        }).toList();
        List<FeatureMatchResultsResponse.RepositorySummary> repositories = new ArrayList<>();
        for (FeatureMatchRepositoryRow repository : view.repositories()) {
            long count = view.prs().stream()
                    .filter(pr -> pr.repositoryId() == repository.id() && matched.contains(pr.id())).count();
            repositories.add(new FeatureMatchResultsResponse.RepositorySummary(
                    repository.id(), repository.fullName(), count));
        }
        return new FeatureMatchResultsResponse(view.run().id(), view.run().status(),
                view.run().finishedAt(), summary, repositories, sections);
    }

    public FeatureMatchDetailResponse detail(long featureId, long userId, Long repositoryId) {
        long projectId = scope.featureProject(featureId, userId);
        View view = scope.result(projectId, userId, false);
        scope.repositoryScope(view, repositoryId);
        FeatureMatchFeatureRow feature = view.features().stream().filter(item -> item.id() == featureId).findFirst()
                .orElseThrow(() -> new NotFoundException(ErrorCode.FEATURE_NOT_ACCESSIBLE));
        List<FeaturePrMatchRow> matches = scopeMatches(view, repositoryId).stream()
                .filter(match -> match.featureId() == featureId).toList();
        Set<Long> matchIds = matches.stream().map(FeaturePrMatchRow::id).collect(Collectors.toSet());
        List<FeatureMatchRequirementLinkRow> links = queryRepository.requirementLinks(view.run().id()).stream()
                .filter(link -> matchIds.contains(link.featurePrMatchId())).toList();
        List<FeatureMatchDetailResponse.Requirement> requirements = new ArrayList<>();
        for (FeatureMatchRequirementRow requirement : view.requirements()) {
            if (requirement.featureId() == featureId) {
                long count = links.stream().filter(link -> link.featureRequirementId() == requirement.id())
                        .map(FeatureMatchRequirementLinkRow::featurePrMatchId).distinct().count();
                requirements.add(new FeatureMatchDetailResponse.Requirement(
                        requirement.id(), requirement.content(), count));
            }
        }
        Map<Long, FeatureMatchPullRequestRow> prs = view.prs().stream()
                .collect(Collectors.toMap(FeatureMatchPullRequestRow::id, pr -> pr));
        List<FeatureMatchDetailResponse.RepositoryGroup> groups = new ArrayList<>();
        for (FeatureMatchRepositoryRow repository : view.repositories()) {
            if (repositoryId != null && repository.id() != repositoryId) {
                continue;
            }
            List<FeatureMatchDetailResponse.Match> items = new ArrayList<>();
            for (FeaturePrMatchRow match : matches) {
                FeatureMatchPullRequestRow pr = prs.get(match.pullRequestId());
                if (pr.repositoryId() == repository.id()) {
                    items.add(detailMatch(match, pr, view.requirements(), links));
                }
            }
            if (!items.isEmpty()) {
                groups.add(new FeatureMatchDetailResponse.RepositoryGroup(
                        repository.id(), repository.fullName(), items.size(), items));
            }
        }
        return new FeatureMatchDetailResponse(view.run().id(), feature.id(), feature.name(),
                feature.reviewStatus(), evidence(!matches.isEmpty()), feature.sectionId(), feature.sectionTitle(),
                repositoryId, feature.sourcePageStart(), feature.sourcePageEnd(), requirements,
                matchedPrIds(matches).size(), groups);
    }

    public UnmatchedPullRequestListResponse unmatched(
            long projectId,
            long userId,
            Long repositoryId,
            String query,
            int page,
            int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        View view = scope.result(projectId, userId, false);
        scope.repositoryScope(view, repositoryId);
        String normalizedQuery = normalize(query);
        Page<FeatureMatchPullRequestRow> rows = queryRepository.unmatched(projectId, view.run().id(),
                view.run().specDocumentId(), repositoryId, normalizedQuery, PageRequest.of(page, size));
        List<FeatureMatchPullRequestResponse> items = rows.getContent().stream()
                .map(FeatureMatchPullRequestResponse::from).toList();
        return new UnmatchedPullRequestListResponse(view.run().id(), items, page, size,
                rows.getTotalElements(), rows.getTotalPages());
    }

    private FeatureMatchResultsResponse.Summary summary(View view, Set<Long> matched) {
        Set<Long> featuresWithEvidence = view.matches().stream()
                .map(FeaturePrMatchRow::featureId).collect(Collectors.toSet());
        long evidence = view.features().stream()
                .filter(feature -> featuresWithEvidence.contains(feature.id())).count();
        long attention = view.features().stream()
                .filter(feature -> !featuresWithEvidence.contains(feature.id()) || unreviewed(feature)).count();
        long unreviewed = view.features().stream().filter(FeatureMatchQueryService::unreviewed).count();
        Set<Long> eligible = eligiblePrIds(view, null);
        Set<Long> completed = eligiblePrIds(view, FeatureMatchTargetStatus.COMPLETED);
        long matchedCount = matched.stream().filter(eligible::contains).count();
        long unmatchedCount = completed.stream().filter(id -> !matched.contains(id)).count();
        List<FeaturePrMatchRow> manual = view.matches().stream()
                .filter(match -> match.source() == FeatureMatchSource.USER).toList();
        long manualOnlyCount = manual.stream().map(FeaturePrMatchRow::pullRequestId)
                .distinct().filter(id -> !eligible.contains(id)).count();
        FeatureMatchCounts counts = queryRepository.counts(view.run().id());
        return new FeatureMatchResultsResponse.Summary(
                view.run().featureCount(), evidence, attention, view.features().size() - evidence,
                unreviewed, view.run().eligiblePrCount(), matchedCount, unmatchedCount,
                counts.failedCount(), counts.cancelledCount(), view.run().excludedFailedPrCount(),
                view.run().excludedCancelledPrCount(), manual.size(), manualOnlyCount);
    }

    private static FeatureMatchDetailResponse.Match detailMatch(
            FeaturePrMatchRow match, FeatureMatchPullRequestRow pr,
            List<FeatureMatchRequirementRow> requirements, List<FeatureMatchRequirementLinkRow> links) {
        Set<Long> ids = links.stream().filter(link -> link.featurePrMatchId() == match.id())
                .map(FeatureMatchRequirementLinkRow::featureRequirementId).collect(Collectors.toSet());
        List<FeatureMatchDetailResponse.MatchedRequirement> related = requirements.stream()
                .filter(requirement -> ids.contains(requirement.id()))
                .map(requirement -> new FeatureMatchDetailResponse.MatchedRequirement(
                        requirement.id(), requirement.content())).toList();
        return new FeatureMatchDetailResponse.Match(match.id(), match.source(), match.reason(), related,
                FeatureMatchPullRequestResponse.from(pr));
    }

    private static List<FeaturePrMatchRow> scopeMatches(View view, Long repositoryId) {
        Set<Long> prs = view.prs().stream().filter(pr -> repositoryId == null || pr.repositoryId() == repositoryId)
                .map(FeatureMatchPullRequestRow::id).collect(Collectors.toSet());
        return view.matches().stream().filter(m -> prs.contains(m.pullRequestId())).toList();
    }

    private static Set<Long> eligiblePrIds(View view, FeatureMatchTargetStatus status) {
        Set<Long> analyses = view.targets().stream().filter(t -> status == null || t.status() == status)
                .map(FeatureMatchTargetRow::pullRequestAnalysisId).collect(Collectors.toSet());
        return view.prs().stream().filter(pr -> pr.analysisId() != null && analyses.contains(pr.analysisId()))
                .map(FeatureMatchPullRequestRow::id).collect(Collectors.toSet());
    }

    private static Set<Long> matchedPrIds(List<FeaturePrMatchRow> matches) {
        return matches.stream().map(FeaturePrMatchRow::pullRequestId).collect(Collectors.toSet());
    }

    private static boolean unreviewed(FeatureMatchFeatureRow feature) {
        return feature.reviewStatus() == FeatureReviewStatus.UNREVIEWED;
    }

    private static FeatureEvidenceStatus evidence(boolean found) {
        return found ? FeatureEvidenceStatus.EVIDENCE_FOUND : FeatureEvidenceStatus.NO_EVIDENCE;
    }

    private static String normalize(String value) {
        if (value != null && value.length() > 200) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
