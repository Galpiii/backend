package com.github.galpiii.galpi.domain.collection.pr;

import com.github.galpiii.galpi.domain.collection.config.CollectionProperties;
import com.github.galpiii.galpi.domain.collection.entity.ChangeStatus;
import com.github.galpiii.galpi.domain.collection.entity.DataCompleteness;
import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import com.github.galpiii.galpi.domain.collection.pipeline.CollectedRepositorySnapshot.CollectedCommit;
import com.github.galpiii.galpi.domain.collection.pipeline.CollectedRepositorySnapshot.CollectedPullRequest;
import com.github.galpiii.galpi.domain.collection.pipeline.CollectedRepositorySnapshot.CollectedPullRequestFile;
import com.github.galpiii.galpi.domain.collection.repository.PrExclusionRepository;
import com.github.galpiii.galpi.domain.collection.secret.SecretContentScanner;
import com.github.galpiii.galpi.domain.github.client.GithubCollectionClient;
import com.github.galpiii.galpi.domain.github.client.dto.GithubCommitResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubPullRequestFileResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubPullRequestResponse;
import com.github.galpiii.galpi.domain.github.exception.GithubRateLimitedException;
import com.github.galpiii.galpi.domain.github.exception.GithubRepositoryUnavailableException;
import com.github.galpiii.galpi.global.util.LogSafe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 병합된 PR을 수집한다.
 *
 * <p>수집 범위는 고정이다.
 *
 * <ul>
 *   <li><b>병합된 PR만.</b> draft·closed-unmerged·open은 기능 구현 근거로 성립하지 않는다</li>
 *   <li><b>저장소당 최신 {@code pr_limit}개.</b> 넘으면 잘라내고 {@code PR_LIMIT_EXCEEDED}를
 *       기록한다. 이 값이 화면에 보여야 한다 — 300개를 넘는 저장소에서는 초기 구현 기능이 PR
 *       근거 없이 코드로만 잡히는데, 그 사실이 안 보이면 사용자는 "왜 근거가 없지"라고 느낀다</li>
 *   <li>{@code pr_since}가 있으면 그 시각 이후 병합된 것만</li>
 * </ul>
 *
 * <p>{@code pr_exclusions}에 있는 PR은 <b>저장은 하되</b> 파이프라인에 넘기지 않는다. 되돌릴 수
 * 있어야 하므로 수집 자체를 건너뛰지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PullRequestCollector {

    private final GithubCollectionClient client;
    private final PullRequestWriter writer;
    private final PrExclusionRepository exclusionRepository;
    private final SecretContentScanner secretContentScanner;
    private final CollectionProperties properties;

    /**
     * @param prSince {@code null}이면 시각 제한 없음
     * @throws GithubRateLimitedException rate limit에 걸리면 그대로 위로 올린다. 워커가 작업을
     *                                    중단하고 재개 시각을 기록해야 한다
     */
    public PullRequestCollectionResult collect(String token, String owner, String repo,
                                               Long repositoryId, int prLimit,
                                               OffsetDateTime prSince) {
        GithubCollectionClient.RequestBudget requestBudget =
                new GithubCollectionClient.RequestBudget(
                        properties.maxPullRequestApiRequests());
        PipelineContentBudget contentBudget =
                new PipelineContentBudget(properties.maxPullRequestContentBytes());
        MergedPullRequests merged = listMergedPullRequests(
                token, owner, repo, prLimit, prSince, requestBudget);
        Set<Integer> excludedNumbers =
                new HashSet<>(exclusionRepository.findExcludedNumbersByRepositoryId(repositoryId));

        List<CollectedPullRequest> forPipeline = new ArrayList<>();
        List<IncompleteReason> reasons = new ArrayList<>();
        int collectedCount = 0;
        int failedCount = 0;
        boolean requestLimitReached = merged.requestLimitReached();

        for (GithubPullRequestResponse summary : merged.items()) {
            try {
                CollectedPullRequest collected = collectOne(token, owner, repo, repositoryId,
                        summary.number(), requestBudget, contentBudget);
                collectedCount++;
                reasons.addAll(collected.incompleteReasons());
                if (!excludedNumbers.contains(summary.number())) {
                    forPipeline.add(collected);
                }
            } catch (GithubCollectionClient.RequestBudgetExceededException e) {
                requestLimitReached = true;
                log.info("[수집] PR API 요청 상한({})에 도달해 나머지를 건너뛴다 repo={}/{}",
                        properties.maxPullRequestApiRequests(), LogSafe.text(owner),
                        LogSafe.text(repo));
                break;
            } catch (GithubRateLimitedException e) {
                throw e;
            } catch (GithubRepositoryUnavailableException e) {
                // 저장소 자체가 사라졌다. 남은 PR을 시도할 이유가 없다.
                throw e;
            } catch (RuntimeException e) {
                // PR 하나가 실패했다고 나머지를 버리지 않는다.
                failedCount++;
                log.warn("[수집] PR 하나를 수집하지 못했다 repo={}/{} number={} cause={}",
                        LogSafe.text(owner), LogSafe.text(repo), summary.number(),
                        e.getClass().getSimpleName());
            }
        }

        if (failedCount > 0) {
            reasons.add(IncompleteReason.PR_COLLECTION_PARTIAL);
        }
        if (merged.exceededLimit()) {
            reasons.add(IncompleteReason.PR_LIMIT_EXCEEDED);
        }
        if (requestLimitReached) {
            reasons.add(IncompleteReason.PR_REQUEST_LIMIT);
        }
        return new PullRequestCollectionResult(List.copyOf(forPipeline), collectedCount,
                List.copyOf(new LinkedHashSet<>(reasons)));
    }

    /**
     * 병합된 PR을 최신순으로 상한까지 모은다.
     *
     * <p>목록은 {@code state=closed}라 병합되지 않고 닫힌 PR이 섞여 온다. 상한은 병합된 것만
     * 세어 적용한다. 페이지 상한을 따로 두는 이유는 병합 PR이 드문 저장소 때문이다 — 닫히기만
     * 한 PR이 수천 개면 상한을 채우지 못한 채 목록만 계속 넘기게 된다.
     *
     * <p>상한을 채운 뒤에도 한 건을 더 확인한다. 정확히 {@code prLimit}개에서 멈추면 "딱 맞은
     * 것"과 "잘린 것"을 구분할 수 없고, 그 구분이 화면에 나가야 한다.
     */
    private MergedPullRequests listMergedPullRequests(String token, String owner, String repo,
                                                      int prLimit, OffsetDateTime prSince,
                                                      GithubCollectionClient.RequestBudget budget) {
        List<GithubPullRequestResponse> merged = new ArrayList<>();
        String nextUri = null;
        int pages = 0;

        do {
            GithubCollectionClient.GithubPage<GithubPullRequestResponse> page;
            try {
                page = client.listClosedPullRequests(token, owner, repo, nextUri, budget);
            } catch (GithubCollectionClient.RequestBudgetExceededException e) {
                return new MergedPullRequests(List.copyOf(merged), false, true);
            }
            pages++;

            for (GithubPullRequestResponse pullRequest : page.items()) {
                if (!pullRequest.isMerged()) {
                    continue;
                }
                if (prSince != null && pullRequest.mergedAt().isBefore(prSince)) {
                    continue;
                }
                if (merged.size() >= prLimit) {
                    return new MergedPullRequests(List.copyOf(merged), true, false);
                }
                merged.add(pullRequest);
            }
            nextUri = page.nextUri();
        } while (nextUri != null && pages < listPageLimit());

        boolean truncatedByPages = nextUri != null;
        if (truncatedByPages) {
            log.info("[수집] PR 목록 페이지 상한({})에 걸려 더 훑지 않는다 repo={}/{}",
                    listPageLimit(), LogSafe.text(owner), LogSafe.text(repo));
        }
        return new MergedPullRequests(List.copyOf(merged), truncatedByPages, false);
    }

    private int listPageLimit() {
        return properties.maxPullRequestListPages();
    }

    /** @param exceededLimit 상한이나 페이지 한계 때문에 뒤쪽 PR을 보지 못했는지 */
    private record MergedPullRequests(List<GithubPullRequestResponse> items,
                                      boolean exceededLimit,
                                      boolean requestLimitReached) {
    }

    private CollectedPullRequest collectOne(String token, String owner, String repo,
                                            Long repositoryId, int number,
                                            GithubCollectionClient.RequestBudget requestBudget,
                                            PipelineContentBudget contentBudget) {
        GithubPullRequestResponse detail =
                client.getPullRequest(token, owner, repo, number, requestBudget);
        GithubCollectionClient.PagedResult<GithubPullRequestFileResponse> files =
                client.listFiles(token, owner, repo, number, requestBudget);
        GithubCollectionClient.PagedResult<GithubCommitResponse> commits =
                client.listCommits(token, owner, repo, number, requestBudget);

        List<IncompleteReason> reasons = new ArrayList<>(incompleteReasons(files, commits));

        // 제목·본문·커밋 메시지는 사람이 자유롭게 쓰는 필드다. 저장 전에 마스킹한다.
        SecretContentScanner.MaskedText maskedTitle = secretContentScanner.mask(detail.title());
        SecretContentScanner.MaskedText maskedBody = secretContentScanner.mask(detail.body());
        if (maskedTitle.masked() || maskedBody.masked()) {
            reasons.add(IncompleteReason.SECRET_REDACTED);
            log.info("[수집] PR 텍스트에서 비밀정보를 마스킹했다 repo={}/{} number={}",
                    LogSafe.text(owner), LogSafe.text(repo), number);
        }
        String pipelineTitle = contentBudget.retain(maskedTitle.text());
        String pipelineBody = contentBudget.retain(maskedBody.text());
        boolean contentLimited = (maskedTitle.text() != null && pipelineTitle == null)
                || (maskedBody.text() != null && pipelineBody == null);

        List<PullRequestWriter.CollectedCommitData> commitData = new ArrayList<>();
        List<CollectedCommit> pipelineCommits = new ArrayList<>();
        for (GithubCommitResponse commit : commits.items()) {
            SecretContentScanner.MaskedText maskedMessage =
                    secretContentScanner.mask(commit.message());
            if (maskedMessage.masked()) {
                reasons.add(IncompleteReason.SECRET_REDACTED);
                log.info("[수집] 커밋 메시지에서 비밀정보를 마스킹했다 repo={}/{} number={} kinds={}",
                        LogSafe.text(owner), LogSafe.text(repo), number,
                        maskedMessage.kindsForLog());
            }
            String message = maskedMessage.text() == null ? "" : maskedMessage.text();
            commitData.add(new PullRequestWriter.CollectedCommitData(commit.sha(), message,
                    limit(commit.authorLogin(), 255), commit.authorGithubId(),
                    authoredAt(commit, detail)));
            String pipelineMessage = contentBudget.retain(message);
            if (pipelineMessage == null && !message.isEmpty()) {
                contentLimited = true;
                pipelineMessage = "";
            }
            pipelineCommits.add(new CollectedCommit(commit.sha(), pipelineMessage,
                    limit(commit.authorLogin(), 255), authoredAt(commit, detail)));
        }

        List<PullRequestWriter.CollectedFileData> fileData = new ArrayList<>();
        List<CollectedPullRequestFile> pipelineFiles = new ArrayList<>();
        for (GithubPullRequestFileResponse file : files.items()) {
            ChangeStatus changeStatus = ChangeStatus.from(file.status());
            boolean omitted = file.isPatchOmitted();
            fileData.add(new PullRequestWriter.CollectedFileData(file.filename(),
                    file.previousFilename(), changeStatus, nullSafe(file.additions()),
                    nullSafe(file.deletions()), nullSafe(file.changes()), omitted));
            String pipelinePatch = null;
            if (file.patch() != null && !file.patch().isEmpty()) {
                if (contentBudget.canFit(file.patch())) {
                    SecretContentScanner.MaskedText maskedPatch =
                            secretContentScanner.mask(file.patch());
                    if (maskedPatch.masked()) {
                        reasons.add(IncompleteReason.SECRET_REDACTED);
                        log.info("[수집] PR patch에서 비밀정보를 마스킹했다 repo={}/{} number={}",
                                LogSafe.text(owner), LogSafe.text(repo), number);
                    }
                    pipelinePatch = contentBudget.retain(maskedPatch.text());
                }
                if (pipelinePatch == null) {
                    contentLimited = true;
                }
            }
            // patch는 여기에만 담긴다. writer로 가는 fileData에는 없다.
            pipelineFiles.add(new CollectedPullRequestFile(file.filename(), file.previousFilename(),
                    changeStatus, nullSafe(file.additions()), nullSafe(file.deletions()),
                    nullSafe(file.changes()), pipelinePatch,
                    omitted || file.patch() != null && pipelinePatch == null));
        }

        if (contentLimited) {
            reasons.add(IncompleteReason.PR_CONTENT_LIMIT);
        }
        List<IncompleteReason> distinctReasons =
                List.copyOf(new LinkedHashSet<>(reasons));

        writer.save(repositoryId, new PullRequestWriter.CollectedPullRequestData(
                detail.id(), detail.number(), maskedTitle.text(), maskedBody.text(), detail.baseRef(),
                detail.headRef(), detail.baseSha(), detail.headSha(), detail.mergeCommitSha(),
                detail.mergedAt(), detail.createdAt(), nullSafe(detail.changedFiles()),
                nullSafe(detail.additions()), nullSafe(detail.deletions()), detail.htmlUrl(),
                authorGithubId(detail), authorLogin(detail), authorAvatarUrl(detail),
                distinctReasons, fileData, commitData));

        return new CollectedPullRequest(detail.number(),
                pipelineTitle == null ? "" : pipelineTitle, pipelineBody,
                detail.mergedAt(), List.copyOf(pipelineFiles), List.copyOf(pipelineCommits),
                distinctReasons.isEmpty() ? DataCompleteness.COMPLETE : DataCompleteness.PARTIAL,
                distinctReasons);
    }

    /**
     * PR 단위 불완전 사유.
     *
     * <p>바이너리는 GitHub이 따로 알려 주지 않는다. patch가 없으면서 변경량도 0인 파일은
     * 바이너리로 본다 — 텍스트 파일이 바뀌었는데 additions와 deletions가 모두 0인 경우는 없다.
     */
    private static List<IncompleteReason> incompleteReasons(
            GithubCollectionClient.PagedResult<GithubPullRequestFileResponse> files,
            GithubCollectionClient.PagedResult<GithubCommitResponse> commits) {
        List<IncompleteReason> reasons = new ArrayList<>();
        if (files.truncated()) {
            reasons.add(IncompleteReason.FILE_LIMIT_EXCEEDED);
        }
        if (commits.truncated()) {
            reasons.add(IncompleteReason.COMMIT_LIMIT_EXCEEDED);
        }

        boolean anyOmitted = false;
        boolean anyBinary = false;
        for (GithubPullRequestFileResponse file : files.items()) {
            if (!file.isPatchOmitted()) {
                continue;
            }
            anyOmitted = true;
            if (nullSafe(file.additions()) == 0 && nullSafe(file.deletions()) == 0) {
                anyBinary = true;
            }
        }
        if (anyOmitted) {
            reasons.add(IncompleteReason.PATCH_OMITTED);
        }
        if (anyBinary) {
            reasons.add(IncompleteReason.BINARY);
        }
        return List.copyOf(new LinkedHashSet<>(reasons));
    }

    /** 커밋 작성 시각이 비어 오면 PR 병합 시각으로 대신한다. NOT NULL 컬럼이라 비울 수 없다. */
    private static OffsetDateTime authoredAt(GithubCommitResponse commit,
                                             GithubPullRequestResponse detail) {
        return commit.authoredAt() != null ? commit.authoredAt() : detail.mergedAt();
    }

    private static Long authorGithubId(GithubPullRequestResponse detail) {
        return detail.user() == null ? null : detail.user().id();
    }

    private static String authorLogin(GithubPullRequestResponse detail) {
        return detail.user() == null ? null : detail.user().login();
    }

    private static String authorAvatarUrl(GithubPullRequestResponse detail) {
        return detail.user() == null ? null : detail.user().avatarUrl();
    }

    private static int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private static String limit(String value, int maxLength) {
        return value == null || value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }

    /** 파이프라인이 한 저장소의 PR 문자열을 무제한으로 붙잡지 않게 하는 누적 budget. */
    private static final class PipelineContentBudget {

        private long remaining;

        private PipelineContentBudget(long maxBytes) {
            this.remaining = Math.max(0, maxBytes);
        }

        private boolean canFit(String value) {
            return value == null || utf8Length(value) <= remaining;
        }

        private String retain(String value) {
            if (value == null || value.isEmpty()) {
                return value;
            }
            long bytes = utf8Length(value);
            if (bytes > remaining) {
                return null;
            }
            remaining -= bytes;
            return value;
        }

        private static long utf8Length(String value) {
            long bytes = 0;
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character <= 0x7f) {
                    bytes++;
                } else if (character <= 0x7ff) {
                    bytes += 2;
                } else if (Character.isHighSurrogate(character)
                        && index + 1 < value.length()
                        && Character.isLowSurrogate(value.charAt(index + 1))) {
                    bytes += 4;
                    index++;
                } else {
                    bytes += 3;
                }
            }
            return bytes;
        }
    }

    /**
     * @param pullRequests  파이프라인에 넘길 PR. {@code pr_exclusions}에 있는 것은 빠져 있다
     * @param collectedCount 저장한 PR 수. 제외된 PR도 포함한다
     */
    public record PullRequestCollectionResult(List<CollectedPullRequest> pullRequests,
                                              int collectedCount,
                                              List<IncompleteReason> incompleteReasons) {
    }
}
