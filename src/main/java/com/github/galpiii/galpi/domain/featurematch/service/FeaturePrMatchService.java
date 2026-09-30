package com.github.galpiii.galpi.domain.featurematch.service;

import com.github.galpiii.galpi.domain.collection.repository.PullRequestRepository;
import com.github.galpiii.galpi.domain.featurematch.dto.request.FeaturePrMatchesCreateRequest;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeaturePrMatchesCreatedResponse;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeaturePrMatchesCreatedResponse.CreatedMatch;
import com.github.galpiii.galpi.domain.featurematch.entity.FeaturePrMatch;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeaturePrMatchRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureRepository;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class FeaturePrMatchService {

    private final FeatureMatchQueryRepository queryRepository;
    private final FeatureMatchScope scope;
    private final FeaturePrMatchRepository matchRepository;
    private final FeatureRepository featureRepository;
    private final PullRequestRepository pullRequestRepository;
    private final UserRepository userRepository;

    @Transactional
    public FeaturePrMatchesCreatedResponse create(long featureId, long userId, FeaturePrMatchesCreateRequest request) {
        FeatureMatchScope.View view = scope.result(scope.featureProject(featureId, userId), userId, true);
        if (view.features().stream().noneMatch(f -> f.id() == featureId)) {
            log.warn("[기능대조] 연결 생성 기능 접근 거절 featureId={} userId={}", featureId, userId);
            throw new NotFoundException(ErrorCode.FEATURE_NOT_ACCESSIBLE);
        }
        List<Long> ids = request.pullRequestIds();
        if (new HashSet<>(ids).size() != ids.size()) {
            log.warn("[기능대조] 연결 요청 ID 중복 featureId={} userId={}", featureId, userId);
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Set<Long> available = view.prs().stream().map(pr -> pr.id()).collect(Collectors.toSet());
        if (!available.containsAll(ids)) {
            log.warn("[기능대조] 연결 요청 PR 범위 거절 featureId={} userId={}", featureId, userId);
            throw new NotFoundException(ErrorCode.PULL_REQUEST_NOT_FOUND);
        }
        if (view.matches().stream().anyMatch(m -> m.featureId() == featureId && ids.contains(m.pullRequestId()))) {
            log.warn("[기능대조] 기존 연결 중복 featureId={} userId={}", featureId, userId);
            throw new ConflictException(ErrorCode.FEATURE_MATCH_DUPLICATED);
        }
        // 프로젝트 잠금 아래에서 전체 검증 후 쓰므로 배열 전체가 성공하거나 전체가 롤백된다.
        List<FeaturePrMatch> matches = matchRepository.saveAll(ids.stream()
                .map(prId -> FeaturePrMatch.byUser(featureRepository.getReferenceById(featureId),
                        pullRequestRepository.getReferenceById(prId), userRepository.getReferenceById(userId)))
                .toList());
        List<CreatedMatch> created = matches.stream()
                .map(match -> new CreatedMatch(match.getId(), match.getFeature().getId(),
                        match.getPullRequest().getId(), match.getSource(), match.getCreatedAt()))
                .toList();
        return new FeaturePrMatchesCreatedResponse(created);
    }

    @Transactional
    public void delete(long matchId, long userId) {
        Long projectId = queryRepository.matchProject(matchId, userId);
        if (projectId == null) {
            log.warn("[기능대조] 연결 삭제 접근 거절 matchId={} userId={}", matchId, userId);
            throw new NotFoundException(ErrorCode.FEATURE_MATCH_NOT_FOUND);
        }
        FeatureMatchScope.View view = scope.result(projectId, userId, true);
        if (view.matches().stream().noneMatch(m -> m.id() == matchId)) {
            log.warn("[기능대조] 연결 삭제 결과 범위 거절 matchId={} userId={}", matchId, userId);
            throw new NotFoundException(ErrorCode.FEATURE_MATCH_NOT_FOUND);
        }
        matchRepository.deleteById(matchId);
    }
}
