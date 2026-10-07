package com.github.galpiii.galpi.domain.featurematch.support;

import com.github.galpiii.galpi.ai.dto.FeatureMatchingRequest;
import com.github.galpiii.galpi.domain.collection.secret.SecretContentScanner;
import com.github.galpiii.galpi.domain.collection.secret.SecretPathRules;
import com.github.galpiii.galpi.domain.featurematch.config.FeatureMatchProperties;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.FeatureRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.FileRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.PrRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.RequirementRow;
import com.github.galpiii.galpi.domain.featurematch.exception.FeatureMatchInputTooLargeException;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 기능 목록은 온전히 유지하고 PR의 부가 근거만 남은 예산에 맞춰 담는다.
 * 마스킹을 절단보다 먼저 적용하고, JSON 이스케이프까지 포함한 실제 길이로 계산한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureMatchInputAssembler {

    private final FeatureMatchQueryRepository queryRepository;
    private final SecretContentScanner scanner;
    private final SecretPathRules paths;
    private final FeatureMatchProperties properties;

    public String assemble(List<FeatureRow> features,
                           List<RequirementRow> requirements,
                           PrRow pr) {
        List<FeatureMatchingRequest.Section> sections = safeSections(features, requirements);
        FeatureMatchingRequest.PullRequest pullRequest = pullRequest(pr, FeatureMatchSnapshot.json(sections).length());
        return FeatureMatchSnapshot.json(new FeatureMatchingRequest(sections, pullRequest));
    }

    public FeatureMatchingRequest.PullRequest pullRequest(PrRow pr, int sectionChars) {
        return pullRequest(pr, sectionChars, pr.summary(),
                pr.changeType() == null ? null : pr.changeType().name(), null, null);
    }

    public FeatureMatchingRequest.PullRequest pullRequest(
            PrRow pr, int sectionChars, List<String> candidates, List<FileRow> changedFiles) {
        return pullRequest(pr, sectionChars, pr.summary(),
                pr.changeType() == null ? null : pr.changeType().name(), candidates, changedFiles);
    }

    public FeatureMatchingRequest.PullRequest sourcePullRequest(
            PrRow pr, int sectionChars, int analysisChars) {
        // 분석 실패로 요약이 비어도 이전 대조의 요약 길이만큼 입력 예산을 예약한다.
        return pullRequest(pr, sectionChars + analysisChars, null, null, null, null);
    }

    public FeatureMatchingRequest.PullRequest sourcePullRequest(
            PrRow pr, int sectionChars, int analysisChars,
            List<String> candidates, List<FileRow> changedFiles) {
        return pullRequest(pr, sectionChars + analysisChars, null, null, candidates, changedFiles);
    }

    private FeatureMatchingRequest.PullRequest pullRequest(
            PrRow pr, int sectionChars, String analysisSummary, String changeType,
            List<String> suppliedCommits, List<FileRow> suppliedFiles) {
        int fieldLimit = Math.max(1, properties.maxInputChars() / 32);
        // 기본 120k 예산에서는 본문 8k를 보존하고, 작은 설정에서만 비례해서 줄인다.
        String body = safe(pr.body(), Math.min(8000, properties.maxInputChars() / 8));
        String summary = safe(analysisSummary, Math.min(1000, fieldLimit));
        String title = safe(pr.title(), Math.min(500, fieldLimit));
        String repositoryName = safe(pr.fullName(), Math.min(200, fieldLimit));
        List<String> commits = new ArrayList<>();
        List<FeatureMatchingRequest.ChangedFile> files = new ArrayList<>();
        FeatureMatchingRequest.PullRequest pullRequest = new FeatureMatchingRequest.PullRequest(
                repositoryName, title, body, summary, changeType, commits, files);
        int remaining = remaining(sectionChars, pullRequest);
        if (remaining < 0) {
            // PR 메타데이터도 예산 밖이라면 최소 텍스트부터 다시 구성한다.
            pullRequest = new FeatureMatchingRequest.PullRequest(
                    safe(repositoryName, 20), safe(title, 20), null, null, changeType, commits, files);
            remaining = remaining(sectionChars, pullRequest);
        }
        if (remaining < 0) {
            throw new FeatureMatchInputTooLargeException();
        }

        List<String> candidates = suppliedCommits == null ? queryRepository.commits(pr.id()) : suppliedCommits;
        int commitBudget = remaining / 2;
        int used = 0;
        for (String message : candidates) {
            String value = safe(message, 500);
            int cost = FeatureMatchSnapshot.json(value).length() + (commits.isEmpty() ? 0 : 1);
            if (used + cost > commitBudget) {
                break;
            }
            commits.add(value);
            used += cost;
        }
        remaining -= used;
        List<FileRow> changedFiles = suppliedFiles == null ? queryRepository.files(pr.id()) : suppliedFiles;
        for (FileRow file : changedFiles) {
            if (paths.isSecretPath(file.path())) {
                continue;
            }
            FeatureMatchingRequest.ChangedFile value = new FeatureMatchingRequest.ChangedFile(
                    safe(file.path(), 500), file.changeStatus(), file.additions(), file.deletions());
            int cost = FeatureMatchSnapshot.json(value).length() + (files.isEmpty() ? 0 : 1);
            if (cost > remaining) {
                break;
            }
            files.add(value);
            remaining -= cost;
        }
        log.info("[기능대조] 입력 구성 prId={} commits={}/{} files={}/{} remainingChars={}",
                pr.id(), commits.size(), candidates.size(), files.size(), changedFiles.size(), remaining);
        return pullRequest;
    }

    private int remaining(int sectionChars, FeatureMatchingRequest.PullRequest pullRequest) {
        int withEmptySections = FeatureMatchSnapshot.json(
                new FeatureMatchingRequest(List.of(), pullRequest)).length();
        return properties.maxInputChars() - withEmptySections + 2 - sectionChars;
    }

    public void checkFeatureSize(List<FeatureRow> features,
                                 List<RequirementRow> requirements) {
        if (FeatureMatchSnapshot.json(safeSections(features, requirements)).length()
                > properties.maxInputChars() / 2) {
            throw new FeatureMatchInputTooLargeException();
        }
    }

    private List<FeatureMatchingRequest.Section> safeSections(
            List<FeatureRow> features, List<RequirementRow> requirements) {
        List<FeatureMatchingRequest.Section> result = new ArrayList<>();
        for (FeatureMatchingRequest.Section section : FeatureMatchSnapshot.sections(features, requirements)) {
            List<FeatureMatchingRequest.Feature> safeFeatures = new ArrayList<>();
            for (FeatureMatchingRequest.Feature feature : section.features()) {
                List<FeatureMatchingRequest.Requirement> safeRequirements = feature.requirements().stream()
                        .map(requirement -> new FeatureMatchingRequest.Requirement(
                                requirement.requirementId(), safe(requirement.content(), null)))
                        .toList();
                safeFeatures.add(new FeatureMatchingRequest.Feature(
                        feature.featureId(), safe(feature.name(), null), safeRequirements));
            }
            result.add(new FeatureMatchingRequest.Section(
                    section.sectionId(), safe(section.title(), null), safeFeatures));
        }
        return result;
    }

    public String safe(String value, Integer max) {
        if (value == null) {
            return null;
        }
        String masked = scanner.mask(value).text();
        if (max == null || masked.length() <= max) {
            return masked;
        }
        // 이미 마스킹된 문자열만 자르며, 표시 문자열도 상한 안에 둔다.
        return max < 2 ? "…" : masked.substring(0, max - 1) + "…";
    }
}
