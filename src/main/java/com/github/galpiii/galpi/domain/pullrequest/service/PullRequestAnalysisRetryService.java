package com.github.galpiii.galpi.domain.pullrequest.service;

import com.github.galpiii.galpi.domain.consent.service.AiDataConsentService;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestAnalysisRetryResponse;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.domain.pullrequest.repository.PullRequestAnalysisRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 실패한 요약을 다시 큐에 넣는다. 화면의 "실패한 PR만 다시 분석"이다.
 *
 * <p>동의를 여기서 확인하는 것이 중요하다. <b>큐에 넣는 이 순간이 사용자 세션이 있는 유일한
 * 지점</b>이고, 워커는 세션 없이 돈다. {@code AnalysisRunService.create()}가 같은 이유로
 * 같은 자리에서 확인한다. 워커도 전송 직전에 한 번 더 묻지만, 그것은 "그 사이에 철회됐는지"를
 * 보는 것이지 처음 확인을 대신하는 것이 아니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PullRequestAnalysisRetryService {

    private final ProjectRepository projectRepository;
    private final GithubRepositoryRepository repositoryRepository;
    private final PullRequestAnalysisRepository analysisRepository;
    private final AiDataConsentService consentService;

    /**
     * @param repositoryId {@code null}이면 프로젝트 전체
     */
    @Transactional
    public PullRequestAnalysisRetryResponse retry(Long userId, Long projectId, Long repositoryId) {
        projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROJECT_NOT_FOUND));
        consentService.requireAgreed(userId);

        OffsetDateTime now = OffsetDateTime.now();
        int requeued = repositoryId == null
                ? analysisRepository.requeueFailedInProject(projectId,
                        PullRequestAnalysisStatus.PENDING, PullRequestAnalysisStatus.FAILED, now)
                : analysisRepository.requeueFailedInRepository(
                        requireLinkedRepository(projectId, repositoryId),
                        PullRequestAnalysisStatus.PENDING, PullRequestAnalysisStatus.FAILED, now);

        log.info("[요약] 실패한 요약을 다시 큐에 넣었다 projectId={} repositoryId={} count={}",
                projectId, repositoryId, requeued);
        return new PullRequestAnalysisRetryResponse(requeued);
    }

    private Long requireLinkedRepository(Long projectId, Long repositoryId) {
        return repositoryRepository.findByIdAndProjectId(repositoryId, projectId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROJECT_REPOSITORY_NOT_FOUND))
                .getId();
    }
}
