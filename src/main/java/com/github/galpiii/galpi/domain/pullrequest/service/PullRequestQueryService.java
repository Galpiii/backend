package com.github.galpiii.galpi.domain.pullrequest.service;

import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import com.github.galpiii.galpi.domain.collection.entity.PullRequestCommit;
import com.github.galpiii.galpi.domain.collection.entity.PullRequestFile;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestCommitRepository;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestFileRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.pullrequest.config.PullRequestProperties;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestDetailResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestListResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestOverviewResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestSort;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysis;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.domain.pullrequest.repository.PullRequestAnalysisRepository;
import com.github.galpiii.galpi.domain.pullrequest.repository.PullRequestQueryRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * PR 목록·집계·상세 조회.
 *
 * <p>모든 조회가 소유권 확인으로 시작한다. 남의 프로젝트·삭제된 프로젝트·없는 프로젝트는
 * 전부 404다 -- {@code ProjectService}가 세워 둔 규칙을 그대로 따른다. 403으로 구분하면
 * id를 하나씩 넣어 보는 것만으로 남이 무엇을 가지고 있는지 알 수 있다.
 *
 * <p>연결을 끊은 저장소({@code unlinked_at})의 PR은 어디에도 나오지 않는다. 프로젝트 상세가
 * 살아 있는 저장소만 세고 있어서, 여기서만 포함하면 "저장소 2개"라고 적힌 화면에 3개 저장소의
 * PR이 섞여 나온다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PullRequestQueryService {

    /** 검색어가 숫자가 아닐 때 쓰는 PR 번호 자리. 번호는 양수라 어떤 행과도 맞지 않는다. */
    private static final int NO_NUMBER = -1;

    /** "필터 없음"을 나타내는 값. GitHub login은 빈 문자열일 수 없다. */
    private static final String NO_FILTER = "";

    private final ProjectRepository projectRepository;
    private final GithubRepositoryRepository repositoryRepository;
    private final PullRequestQueryRepository queryRepository;
    private final PullRequestAnalysisRepository analysisRepository;
    private final PullRequestFileRepository fileRepository;
    private final PullRequestCommitRepository commitRepository;
    private final PullRequestProperties properties;

    @Transactional(readOnly = true)
    public PullRequestListResponse list(Long userId, Long projectId, Long repositoryId,
                                        String authorLogin, String query,
                                        PullRequestAnalysisStatus analysisStatus,
                                        PullRequestSort sort, Integer page, Integer size) {
        requireProject(userId, projectId);

        List<Long> repositoryIds = resolveRepositoryIds(projectId, repositoryId);
        int resolvedPage = page == null || page < 0 ? 0 : page;
        int resolvedSize = resolvePageSize(size);
        if (repositoryIds.isEmpty()) {
            // 저장소가 없으면 조건이 항상 거짓이다. IN ()을 만들지 않으려고 여기서 접는다.
            return PullRequestListResponse.empty(resolvedPage, resolvedSize);
        }

        PullRequestSort resolvedSort = sort == null ? PullRequestSort.MERGED_AT_DESC : sort;
        Pageable pageable = PageRequest.of(resolvedPage, resolvedSize, resolvedSort.toSort());

        String normalizedAuthor = normalize(authorLogin);
        String normalizedQuery = normalize(query);

        return PullRequestListResponse.from(queryRepository.findListRows(
                repositoryIds,
                normalizedAuthor,
                analysisStatus != null,
                analysisStatus == null ? List.of(PullRequestAnalysisStatus.values())
                        : List.of(analysisStatus),
                normalizedQuery,
                "%" + normalizedQuery + "%",
                isNumeric(normalizedQuery),
                toNumber(normalizedQuery),
                pageable));
    }

    /**
     * 헤더 집계.
     *
     * <p>목록과 분리한 이유는 두 값의 성격이 달라서다. 목록은 필터에 따라 바뀌지만 헤더 숫자는
     * 언제나 프로젝트 전체 기준이고, 필터를 바꿀 때마다 전체 집계를 다시 돌 이유가 없다.
     */
    @Transactional(readOnly = true)
    public PullRequestOverviewResponse overview(Long userId, Long projectId) {
        requireProject(userId, projectId);

        return PullRequestOverviewResponse.from(queryRepository.countByRepository(
                projectId,
                PullRequestAnalysisStatus.FAILED,
                List.of(PullRequestAnalysisStatus.PENDING, PullRequestAnalysisStatus.RUNNING)));
    }

    /**
     * 상세.
     *
     * <p>경로에 {@code projectId}가 없다. {@code pullRequestId}가 전역 고유하고 소유권은
     * {@code pull_requests -> repositories -> projects.owner_id}로 확인할 수 있어서,
     * {@code /feature-specs/{specDocumentId}}와 같은 형태를 쓴다.
     */
    @Transactional(readOnly = true)
    public PullRequestDetailResponse detail(Long userId, Long pullRequestId) {
        PullRequest pullRequest = queryRepository.findDetail(pullRequestId, userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PULL_REQUEST_NOT_FOUND));

        // 상한보다 하나 더 읽어 "잘렸는지"를 개수 비교 없이 판단한다. count 쿼리를 한 번 더
        // 도는 것보다 싸고, changed_files_count와 비교하는 것보다 정확하다 -- 그 값은 GitHub이
        // 보고한 수라 수집이 잘린 PR에서는 파일 행 수와 애초에 다르다.
        int fileLimit = properties.maxDetailFiles();
        List<PullRequestFile> files = fileRepository.findAllByPullRequestId(
                pullRequestId,
                PageRequest.of(0, fileLimit + 1, Sort.by(Sort.Direction.ASC, "id")));
        boolean filesTruncated = files.size() > fileLimit;
        if (filesTruncated) {
            files = files.subList(0, fileLimit);
        }

        int commitLimit = properties.maxDetailCommits();
        List<PullRequestCommit> commits = commitRepository.findAllByPullRequestId(
                pullRequestId,
                PageRequest.of(0, commitLimit + 1, Sort.by(Sort.Direction.ASC, "id")));
        boolean commitsTruncated = commits.size() > commitLimit;
        if (commitsTruncated) {
            commits = commits.subList(0, commitLimit);
        }
        PullRequestAnalysis analysis =
                analysisRepository.findByPullRequestId(pullRequestId).orElse(null);

        return PullRequestDetailResponse.of(pullRequest, pullRequest.getRepository(), files,
                filesTruncated, commits, commitsTruncated, analysis);
    }

    private void requireProject(Long userId, Long projectId) {
        projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROJECT_NOT_FOUND));
    }

    /**
     * 목록을 좁힐 저장소.
     *
     * <p>{@code repositoryId}를 받았으면 그 저장소가 이 프로젝트의 것인지 여기서 확인한다.
     * 확인하지 않으면 남의 저장소 id를 넣어 그 저장소의 PR을 볼 수 있다.
     */
    private List<Long> resolveRepositoryIds(Long projectId, Long repositoryId) {
        if (repositoryId == null) {
            return repositoryRepository.findAllByProjectId(projectId).stream()
                    .map(repository -> repository.getId())
                    .toList();
        }
        return repositoryRepository.findByIdAndProjectId(repositoryId, projectId)
                .map(repository -> List.of(repository.getId()))
                .orElseThrow(() ->
                        new NotFoundException(ErrorCode.PROJECT_REPOSITORY_NOT_FOUND));
    }

    private int resolvePageSize(Integer size) {
        if (size == null || size < 1) {
            return properties.defaultPageSize();
        }
        return Math.min(size, properties.maxPageSize());
    }

    /** 소문자로 맞추고 널을 빈 문자열로 접는다. 빈 문자열이 "필터 없음"이다. */
    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? NO_FILTER
                : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isNumeric(String value) {
        return !value.isEmpty() && value.chars().allMatch(Character::isDigit);
    }

    /**
     * 숫자 검색어를 PR 번호로 바꾼다.
     *
     * <p>{@code int} 범위를 넘는 숫자는 번호가 될 수 없으므로 없는 번호로 접는다. 그때도
     * 제목 부분 일치는 그대로 동작한다.
     */
    private static int toNumber(String value) {
        if (!isNumeric(value)) {
            return NO_NUMBER;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return NO_NUMBER;
        }
    }
}
