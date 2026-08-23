package com.github.galpiii.galpi.domain.collection;

import com.github.galpiii.galpi.domain.collection.archive.DownloadedArchive;
import com.github.galpiii.galpi.domain.collection.archive.ExtractedRepository;
import com.github.galpiii.galpi.domain.collection.archive.TarballDownloader;
import com.github.galpiii.galpi.domain.collection.archive.TarballExtractor;
import com.github.galpiii.galpi.domain.collection.entity.DataCompleteness;
import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import com.github.galpiii.galpi.domain.collection.file.FileSelectionResult;
import com.github.galpiii.galpi.domain.collection.file.RepositoryFileSelector;
import com.github.galpiii.galpi.domain.collection.pipeline.AnalysisPipelinePort;
import com.github.galpiii.galpi.domain.collection.pipeline.CollectedRepositorySnapshot;
import com.github.galpiii.galpi.domain.collection.pr.PullRequestCollector;
import com.github.galpiii.galpi.domain.github.client.GithubCollectionClient;
import com.github.galpiii.galpi.domain.github.client.dto.GithubRepositoryResponse;
import com.github.galpiii.galpi.global.util.LogSafe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 저장소 하나를 수집해 분석 파이프라인에 넘긴다.
 *
 * <p>순서에 이유가 있다. PR을 먼저 모으고 tarball을 나중에 받는다 — PR 수집은 호출이 최대
 * 900번이라 rate limit에 걸릴 가능성이 가장 높은 구간인데, 여기서 걸리면 200MB를 내려받는 일
 * 자체를 하지 않게 된다.
 *
 * <p>임시 파일 정리는 try-with-resources가 보장한다. 파이프라인 인계 중에 예외가 나도 코드
 * 원문은 디스크에 남지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepositoryCollector {

    private final GithubCollectionClient client;
    private final TarballDownloader downloader;
    private final TarballExtractor extractor;
    private final RepositoryFileSelector fileSelector;
    private final PullRequestCollector pullRequestCollector;
    private final AnalysisPipelinePort pipeline;

    public RepositoryCollectionResult collect(CollectionRequest request) {
        GithubRepositoryResponse repository = client.getRepository(
                request.installationToken(), request.owner(), request.repo());
        String defaultBranch = repository.defaultBranch();
        String commitSha = client.getCommitSha(
                request.installationToken(), request.owner(), request.repo(), defaultBranch);

        PullRequestCollector.PullRequestCollectionResult pullRequests =
                pullRequestCollector.collect(request.installationToken(), request.owner(),
                        request.repo(), request.repositoryId(), request.prLimit(),
                        request.prSince());

        try (DownloadedArchive archive = downloader.download(request.owner(), request.repo(),
                commitSha, request.installationToken());
             ExtractedRepository extracted = extractor.extract(archive)) {

            FileSelectionResult selection = fileSelector.select(
                    extracted, request.includePaths(), request.excludePaths());

            List<IncompleteReason> reasons = new ArrayList<>(selection.incompleteReasons());
            reasons.addAll(pullRequests.incompleteReasons());
            List<IncompleteReason> distinct = List.copyOf(new LinkedHashSet<>(reasons));

            CollectedRepositorySnapshot snapshot = new CollectedRepositorySnapshot(
                    request.githubRepositoryId(),
                    repository.fullName(),
                    commitSha,
                    selection.fileTree(),
                    selection.collectedFiles(),
                    selection.excludedFiles(),
                    pullRequests.pullRequests(),
                    distinct.isEmpty() ? DataCompleteness.COMPLETE : DataCompleteness.PARTIAL,
                    distinct);

            // 인계는 이 블록 안에서만 유효하다. 파이프라인이 스냅샷을 들고 나가면 참조가 가리키는
            // 임시 파일은 이미 지워진 뒤다.
            pipeline.accept(snapshot);

            log.info("[수집] 저장소 수집 완료 repo={} commit={} files={} excluded={} prs={}",
                    LogSafe.text(repository.fullName()), abbreviate(commitSha),
                    selection.collectedFiles().size(), selection.excludedFiles().size(),
                    pullRequests.collectedCount());

            return new RepositoryCollectionResult(
                    commitSha,
                    defaultBranch,
                    repository,
                    selection.collectedFiles().size(),
                    selection.collectedBytes(),
                    selection.excludedFiles().size(),
                    pullRequests.collectedCount(),
                    distinct);
        }
    }

    private static String abbreviate(String sha) {
        return sha == null || sha.length() <= 7 ? sha : sha.substring(0, 7);
    }

    /**
     * @param excludePaths {@code analysis_configs.exclude_paths}. 없으면 빈 목록
     */
    public record CollectionRequest(String installationToken,
                                    String owner,
                                    String repo,
                                    Long repositoryId,
                                    Long githubRepositoryId,
                                    int prLimit,
                                    OffsetDateTime prSince,
                                    List<String> includePaths,
                                    List<String> excludePaths) {
    }

    /** 워커가 {@code analysis_run_repositories}에 기록할 값. */
    public record RepositoryCollectionResult(String commitSha,
                                             String defaultBranch,
                                             GithubRepositoryResponse repository,
                                             int collectedFileCount,
                                             long collectedBytes,
                                             int excludedFileCount,
                                             int prCollectedCount,
                                             List<IncompleteReason> incompleteReasons) {
    }
}
