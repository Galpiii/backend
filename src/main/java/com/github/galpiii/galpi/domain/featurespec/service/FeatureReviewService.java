package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.dto.request.FeatureMergeRequest;
import com.github.galpiii.galpi.domain.featurespec.dto.request.FeatureSplitRequest;
import com.github.galpiii.galpi.domain.featurespec.dto.request.FeatureUpdateRequest;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureReviewResponse;
import com.github.galpiii.galpi.domain.featurespec.entity.DuplicateCandidate;
import com.github.galpiii.galpi.domain.featurespec.entity.Feature;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureIssueType;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureRequirement;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureSection;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.entity.SplitFeatureSuggestion;
import com.github.galpiii.galpi.domain.featurespec.repository.DuplicateCandidateRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureIssueRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureRequirementRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureSectionRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.SplitFeatureSuggestionRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.SplitSuggestionFeatureRequirementRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * LLM이 추출한 기능 목록을 사용자가 확인하고 고치는 단계.
 *
 * <p>검토는 필수가 아니다. 확인하지 않은 기능이 남아 있어도 기능-PR 대조를 막지 않는다.
 * 프론트가 확인창을 띄울 뿐이다.
 *
 * <p>검토가 끝난 기능에서는 AI 산출물(특이사항·중복 후보·분리 제안)을 지운다. 그것들은
 * "AI 제안을 따를 것인가"를 묻는 질문지이고, 답이 나오면 남겨 둘 이유가 없다. 덕분에
 * <b>AI 산출물이 남아 있다 ⟺ 아직 확인하지 않은 기능</b>이 항상 성립한다.
 *
 * <p>트랜잭션 경계를 별도 컴포넌트로 빼지 않는다. 이 도메인의 다른 Writer들은 GitHub 호출이나
 * LLM 호출을 트랜잭션 밖에 두려고 나뉜 것인데, 검토는 DB만 만진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureReviewService {

    /** V15 마이그레이션의 제약 이름. 바꾸면 409가 조용히 500으로 돌아간다. */
    private static final String UNIQUE_FEATURE_SECTIONS_TITLE = "uk_feature_sections_title";

    private final SpecDocumentRepository specDocumentRepository;
    private final FeatureRepository featureRepository;
    private final FeatureSectionRepository featureSectionRepository;
    private final FeatureRequirementRepository featureRequirementRepository;
    private final FeatureIssueRepository featureIssueRepository;
    private final DuplicateCandidateRepository duplicateCandidateRepository;
    private final SplitFeatureSuggestionRepository splitFeatureSuggestionRepository;
    private final SplitSuggestionFeatureRequirementRepository splitSuggestionFeatureRequirementRepository;

    /**
     * 기능명과 세부 요구사항을 고친다.
     *
     * <p>사용자가 직접 고쳤다는 것은 AI 제안을 따르지 않기로 했다는 뜻이므로 이 기능의 AI
     * 산출물을 전부 지운다. 분리 제안을 남겨 두면 특히 위험하다 — 제안은 고치기 전의
     * 요구사항 집합에 대해 계산된 것이라, 요구사항을 하나 더한 뒤 분리를 적용하면 그
     * 요구사항이 어느 추천 기능에도 속하지 않아 소리 없이 사라진다.
     */
    @Transactional
    public FeatureReviewResponse.Feature update(Long specDocumentId, Long userId,
                                                Long featureId, FeatureUpdateRequest request) {
        if (request.isEmpty()) {
            throw new BadRequestException(ErrorCode.FEATURE_UPDATE_EMPTY);
        }

        Feature feature = requireOwnedFeature(specDocumentId, userId, featureId);

        if (request.name() != null) {
            feature.rename(request.name());
        }
        if (request.requirements() != null) {
            replaceRequirements(feature, request.requirements());
        }

        feature.markModified();

        FeatureReviewResponse.Feature response = FeatureReviewResponse.Feature.of(
                feature,
                featureRequirementRepository.findAllByFeatureIdOrderByDisplayOrderAscIdAsc(featureId).stream()
                        .map(FeatureReviewResponse.Requirement::from)
                        .toList(),
                List.of(),
                List.of(),
                List.of()
        );

        clearAiOutput(List.of(featureId));
        clearIncomingDuplicateCandidates(specDocumentId, List.of(featureId));

        log.info("[기능 검토] 기능을 수정했습니다. featureId: {}, userId: {}", featureId, userId);

        return response;
    }

    /** 추출 결과를 그대로 쓴다. 특이사항까지 확인했지만 고칠 것은 없다는 뜻이다. */
    @Transactional
    public void confirm(Long specDocumentId, Long userId, Long featureId) {
        Feature feature = requireOwnedFeature(specDocumentId, userId, featureId);

        feature.confirm();
        clearAiOutput(List.of(featureId));

        log.info("[기능 검토] 기능을 승인했습니다. featureId: {}, userId: {}", featureId, userId);
    }

    /**
     * 중복으로 지목된 두 기능을 새 기능 하나로 합치고 원래 두 기능은 지운다.
     *
     * <p>둘 중 하나를 살려 고쳐 쓰지 않는 이유는, 살리는 쪽의 특이사항·중복 후보·분리 제안을
     * 일일이 지워야 하기 때문이다. 지우면 DB의 ON DELETE CASCADE가 그 일을 대신한다.
     *
     * <p>두 기능의 요구사항을 모두 옮긴다. 비슷해 보이는 것이 있어도 서버가 임의로 합치거나
     * 버리지 않는다 — 무엇이 같은 요구사항인지는 사용자가 판단할 몫이고, 필요하면 합친 뒤
     * 일반 수정으로 지우면 된다.
     */
    @Transactional
    public void merge(Long specDocumentId, Long userId,
                      Long featureId, FeatureMergeRequest request) {
        Feature source = requireOwnedFeaturePair(specDocumentId, userId, featureId, request.targetFeatureId());

        DuplicateCandidate candidate = duplicateCandidateRepository
                .findByFeatureIdAndTargetFeatureId(featureId, request.targetFeatureId())
                .orElseThrow(() -> {
                    log.warn(
                            "[기능 검토] 중복으로 지목되지 않은 기능을 합치려 했습니다. featureId: {}, targetFeatureId: {}",
                            featureId,
                            request.targetFeatureId()
                    );
                    return new BadRequestException(ErrorCode.FEATURE_MERGE_NOT_ALLOWED);
                });

        Feature target = candidate.getTargetFeature();
        SpecDocument specDocument = source.getSpecDocument();

        Feature merged = featureRepository.save(Feature.builder()
                .specDocument(specDocument)
                .section(resolveSections(specDocument, specDocumentId, List.of(candidate.getSuggestedSection()))
                        .get(candidate.getSuggestedSection()))
                .name(request.name())
                // 합쳐진 기능은 둘 중 앞선 자리에 놓는다.
                .displayOrder(Math.min(source.getDisplayOrder(), target.getDisplayOrder()))
                .sourcePageStart(smaller(source.getSourcePageStart(), target.getSourcePageStart()))
                .sourcePageEnd(larger(source.getSourcePageEnd(), target.getSourcePageEnd()))
                .reviewStatus(FeatureReviewStatus.USER_MODIFIED)
                .build());

        List<FeatureRequirement> requirements = new ArrayList<>(
                featureRequirementRepository.findAllByFeatureIdOrderByDisplayOrderAscIdAsc(featureId));
        requirements.addAll(
                featureRequirementRepository.findAllByFeatureIdOrderByDisplayOrderAscIdAsc(target.getId()));
        copyRequirements(merged, requirements);

        deleteFeatures(specDocumentId, List.of(featureId, target.getId()));

        log.info(
                "[기능 검토] 기능을 합쳤습니다. featureIds: [{}, {}], mergedFeatureId: {}, userId: {}",
                featureId,
                target.getId(),
                merged.getId(),
                userId
        );
    }

    /**
     * 저장된 분리 추천안대로 기능 하나를 여러 기능으로 나누고 원래 기능은 지운다.
     *
     * <p>기존 요구사항 행을 옮기지 않고 내용을 복사해 새로 만든다. 옮기면 그 행을 참조하던
     * 분리 제안 연결이 살아남아 이미 적용된 추천안을 계속 가리킨다.
     */
    @Transactional
    public void split(Long specDocumentId, Long userId,
                      Long featureId, FeatureSplitRequest request) {
        Feature source = requireOwnedFeature(specDocumentId, userId, featureId);

        List<SplitFeatureSuggestion> suggestions =
                splitFeatureSuggestionRepository.findAllByFeatureIdOrderByDisplayOrderAscIdAsc(featureId);
        Map<Long, String> namesBySuggestionId = requestedNames(featureId, suggestions, request);

        Map<Long, List<FeatureRequirement>> requirementsBySuggestionId =
                splitSuggestionFeatureRequirementRepository.findRequirementsBySuggestionId(
                        suggestions.stream().map(SplitFeatureSuggestion::getId).toList());
        Map<String, FeatureSection> sections = resolveSections(
                source.getSpecDocument(),
                specDocumentId,
                suggestions.stream().map(SplitFeatureSuggestion::getSuggestedSection).toList());

        List<Long> createdIds = new ArrayList<>();

        for (SplitFeatureSuggestion suggestion : suggestions) {
            Feature created = featureRepository.save(Feature.builder()
                    .specDocument(source.getSpecDocument())
                    .section(sections.get(suggestion.getSuggestedSection()))
                    .name(namesBySuggestionId.get(suggestion.getId()))
                    // 나뉜 기능들은 원래 기능의 자리를 그대로 물려받는다. 값이 같아도 목록
                    // 정렬이 id를 2차 기준으로 쓰므로 만들어진 순서대로 늘어선다.
                    .displayOrder(source.getDisplayOrder())
                    .sourcePageStart(source.getSourcePageStart())
                    .sourcePageEnd(source.getSourcePageEnd())
                    .reviewStatus(FeatureReviewStatus.USER_MODIFIED)
                    .build());

            copyRequirements(created, requirementsBySuggestionId.getOrDefault(suggestion.getId(), List.of()));
            createdIds.add(created.getId());
        }

        deleteFeatures(specDocumentId, List.of(featureId));

        log.info(
                "[기능 검토] 기능을 나눴습니다. featureId: {}, createdFeatureIds: {}, userId: {}",
                featureId,
                createdIds,
                userId
        );
    }

    /** 잘못 추출된 기능을 지운다. 복원과 삭제 이력은 제공하지 않는다. */
    @Transactional
    public void delete(Long specDocumentId, Long userId, Long featureId) {
        requireOwnedFeature(specDocumentId, userId, featureId);

        deleteFeatures(specDocumentId, List.of(featureId));

        log.info("[기능 검토] 기능을 삭제했습니다. featureId: {}, userId: {}", featureId, userId);
    }

    /**
     * 아직 확인하지 않은 기능을 전부 승인한다. 특이사항이 있는 기능도 포함한다.
     *
     * <p>"AI가 알려준 특이사항까지 봤지만 고칠 것 없이 이대로 쓰겠다"는 선택이다. 이미
     * 승인했거나 수정한 기능은 건드리지 않는다.
     */
    @Transactional
    public void confirmAll(Long specDocumentId, Long userId) {
        requireSpecDocument(specDocumentId, userId);
        specDocumentRepository.lockById(specDocumentId);

        List<Long> unreviewedIds = featureRepository.findIdsByReviewStatus(
                specDocumentId, FeatureReviewStatus.UNREVIEWED);

        if (unreviewedIds.isEmpty()) {
            return;
        }

        clearAiOutput(unreviewedIds);
        int confirmed = featureRepository.confirmAllUnreviewed(
                specDocumentId,
                FeatureReviewStatus.UNREVIEWED,
                FeatureReviewStatus.USER_CONFIRMED,
                OffsetDateTime.now()
        );

        log.info(
                "[기능 검토] 남은 기능을 일괄 승인했습니다. specDocumentId: {}, count: {}, userId: {}",
                specDocumentId,
                confirmed,
                userId
        );
    }

    /**
     * 보낸 목록이 곧 저장 후의 목록이 되도록 맞춘다.
     *
     * <p>본문에 실려 온 id가 정말 이 기능의 요구사항인지 먼저 본다. 확인하지 않으면 id만
     * 바꿔 보내는 것으로 남의 기능, 나아가 남의 프로젝트에 있는 요구사항을 고치거나 지울 수 있다.
     *
     * <p>같은 id가 두 번 실려 오는 것도 거절한다. 한 행을 두 항목이 가리키면 뒤에 온 값이
     * 앞의 것을 덮어써 요청한 것보다 적은 요구사항이 남고, 그 자리를 차지했어야 할 다른
     * 요구사항이 목록에서 빠진 것으로 취급되어 삭제된다.
     */
    private void replaceRequirements(Feature feature, List<FeatureUpdateRequest.Requirement> requested) {
        Map<Long, FeatureRequirement> owned = featureRequirementRepository
                .findAllByFeatureIdOrderByDisplayOrderAscIdAsc(feature.getId()).stream()
                .collect(Collectors.toMap(FeatureRequirement::getId, Function.identity()));

        List<Long> requestedIds = requested.stream()
                .map(FeatureUpdateRequest.Requirement::id)
                .filter(Objects::nonNull)
                .toList();
        Set<Long> keptIds = new HashSet<>(requestedIds);

        if (keptIds.size() != requestedIds.size()) {
            log.warn(
                    "[기능 검토] 같은 세부 요구사항을 두 번 보냈습니다. featureId: {}",
                    feature.getId()
            );
            throw new BadRequestException(ErrorCode.FEATURE_REQUIREMENT_DUPLICATED);
        }

        if (!owned.keySet().containsAll(keptIds)) {
            log.warn(
                    "[기능 검토] 이 기능의 것이 아닌 세부 요구사항을 고치려 했습니다. featureId: {}",
                    feature.getId()
            );
            throw new BadRequestException(ErrorCode.FEATURE_REQUIREMENT_NOT_OWNED);
        }

        List<FeatureRequirement> removed = owned.values().stream()
                .filter(requirement -> !keptIds.contains(requirement.getId()))
                .toList();
        featureRequirementRepository.deleteAll(removed);

        for (int order = 0; order < requested.size(); order++) {
            FeatureUpdateRequest.Requirement requirement = requested.get(order);

            if (requirement.id() == null) {
                // 사용자가 직접 쓴 문장이라 대응하는 원문이 없다.
                featureRequirementRepository.save(FeatureRequirement.builder()
                        .feature(feature)
                        .content(requirement.content())
                        .displayOrder(order)
                        .build());
                continue;
            }

            // 원문 근거는 그대로 둔다. 그 문장이 어디서 왔는지에 대한 기록이라 편집 대상이 아니다.
            FeatureRequirement existing = owned.get(requirement.id());
            existing.updateContent(requirement.content());
            existing.updateDisplayOrder(order);
        }
    }

    private void copyRequirements(Feature feature, List<FeatureRequirement> sources) {
        for (int order = 0; order < sources.size(); order++) {
            FeatureRequirement source = sources.get(order);

            featureRequirementRepository.save(FeatureRequirement.builder()
                    .feature(feature)
                    .content(source.getContent())
                    .sourceText(source.getSourceText())
                    .displayOrder(order)
                    .build());
        }
    }

    private Map<Long, String> requestedNames(Long featureId,
                                             List<SplitFeatureSuggestion> suggestions,
                                             FeatureSplitRequest request) {
        Map<Long, String> namesBySuggestionId = request.features().stream()
                .collect(Collectors.toMap(
                        FeatureSplitRequest.Target::suggestionId,
                        FeatureSplitRequest.Target::name,
                        (first, second) -> first,
                        LinkedHashMap::new));

        if (namesBySuggestionId.size() != request.features().size()) {
            log.warn("[기능 검토] 같은 분리 추천안을 두 번 보냈습니다. featureId: {}", featureId);
            throw new BadRequestException(ErrorCode.FEATURE_SPLIT_NOT_ALLOWED);
        }

        Set<Long> suggestionIds = suggestions.stream()
                .map(SplitFeatureSuggestion::getId)
                .collect(Collectors.toSet());

        // 추천안 전체를 그대로 적용하는 것만 허용한다. 일부만 보내면 나머지 추천 기능이
        // 가져갈 요구사항이 갈 곳을 잃는다.
        if (suggestions.isEmpty() || !suggestionIds.equals(namesBySuggestionId.keySet())) {
            log.warn(
                    "[기능 검토] 분리 추천안과 맞지 않는 요청입니다. featureId: {}, 저장된 추천안: {}, 요청: {}",
                    featureId,
                    suggestionIds,
                    namesBySuggestionId.keySet()
            );
            throw new BadRequestException(ErrorCode.FEATURE_SPLIT_NOT_ALLOWED);
        }

        return namesBySuggestionId;
    }

    /**
     * 병합·분리가 제안한 분류명을 실제 분류로 바꾼다. 같은 이름이 없으면 새로 만든다.
     *
     * <p>새로 만드는 분류는 원문에 있던 분류가 아니라 검토 결과로 생긴 논리적 묶음이라
     * 원문 제목과 페이지 범위가 비어 있다.
     */
    private Map<String, FeatureSection> resolveSections(SpecDocument specDocument, Long specDocumentId,
                                                        Collection<String> titles) {
        // 호출부는 저장된 제안 문자열을 그대로 들고 결과를 찾는다. 다듬은 제목을 키로 돌려주면
        // 앞뒤 공백이 붙은 제안에서 조회가 빗나가 분류가 조용히 미분류로 떨어진다.
        Map<String, String> strippedByRequested = new LinkedHashMap<>();

        for (String title : titles) {
            if (title != null && !title.isBlank()) {
                strippedByRequested.put(title, title.strip());
            }
        }

        if (strippedByRequested.isEmpty()) {
            return Map.of();
        }

        Set<String> wanted = new LinkedHashSet<>(strippedByRequested.values());
        Map<String, FeatureSection> byTitle = featureSectionRepository
                .findAllBySpecDocumentIdAndTitleIn(specDocumentId, wanted).stream()
                .collect(Collectors.toMap(
                        FeatureSection::getTitle, Function.identity(), (first, second) -> first,
                        LinkedHashMap::new));

        int nextOrder = featureSectionRepository
                .findTop1BySpecDocumentIdOrderByDisplayOrderDescIdDesc(specDocumentId).stream()
                .mapToInt(section -> section.getDisplayOrder() + 1)
                .findFirst()
                .orElse(0);

        for (String title : wanted) {
            if (byTitle.containsKey(title)) {
                continue;
            }

            byTitle.put(title, createSection(specDocument, title, nextOrder++));
        }

        Map<String, FeatureSection> resolved = new LinkedHashMap<>();
        strippedByRequested.forEach((requested, stripped) -> resolved.put(requested, byTitle.get(stripped)));

        return resolved;
    }

    private FeatureSection createSection(SpecDocument specDocument, String title, int displayOrder) {
        try {
            return featureSectionRepository.saveAndFlush(FeatureSection.builder()
                    .specDocument(specDocument)
                    .title(title)
                    .displayOrder(displayOrder)
                    .build());
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicatedSectionTitle(e)) {
                throw e;
            }

            log.warn(
                    "[기능 검토] 같은 분류가 동시에 만들어져 되돌립니다. specDocumentId: {}, title: {}",
                    specDocument.getId(),
                    title
            );
            throw new ConflictException(ErrorCode.FEATURE_REVIEW_CONFLICT);
        }
    }

    private static boolean isDuplicatedSectionTitle(DataIntegrityViolationException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return UNIQUE_FEATURE_SECTIONS_TITLE.equalsIgnoreCase(violation.getConstraintName());
            }
        }

        return false;
    }

    /**
     * 검토가 끝난 기능의 AI 산출물을 지운다.
     *
     * <p>분리 제안에 딸린 요구사항 연결은 DB의 ON DELETE CASCADE가 함께 지운다.
     */
    private void clearAiOutput(Collection<Long> featureIds) {
        featureIssueRepository.deleteByFeatureIds(featureIds);
        duplicateCandidateRepository.deleteByFeatureIds(featureIds);
        splitFeatureSuggestionRepository.deleteByFeatureIds(featureIds);
    }

    /**
     * 이 기능을 상대로 지목한 후보를 무효화한다.
     *
     * <p>승인에는 하지 않는다. 승인은 그 기능에 붙은 특이사항에 대한 답이고, 중복 여부는
     * 지목한 쪽 카드에서만 묻는 질문이라 승인으로 답한 적이 없다. 반면 수정은 제안의 근거인
     * 요구사항을 바꾸므로 제안 자체가 성립하지 않게 된다.
     */
    private void clearIncomingDuplicateCandidates(Long specDocumentId, Collection<Long> featureIds) {
        duplicateCandidateRepository.deleteByTargetFeatureIds(featureIds);
        featureIssueRepository.deleteOrphanDuplicateIssues(
                specDocumentId, FeatureIssueType.DUPLICATE_SUSPECTED);
    }

    /**
     * 기능을 실제로 지운다.
     */
    private void deleteFeatures(Long specDocumentId, List<Long> featureIds) {
        featureRepository.deleteByIds(featureIds, specDocumentId);
        featureIssueRepository.deleteOrphanDuplicateIssues(
                specDocumentId, FeatureIssueType.DUPLICATE_SUSPECTED);
    }

    /**
     * 작은 id부터 잠근다. 순서가 엇갈리면 반대 방향 병합과 교착한다.
     */
    private Feature requireOwnedFeaturePair(Long specDocumentId, Long userId,
                                            Long featureId, Long targetFeatureId) {
        long first = Math.min(featureId, targetFeatureId);
        long second = Math.max(featureId, targetFeatureId);

        requireSpecDocument(specDocumentId, userId);
        specDocumentRepository.lockById(specDocumentId);
        Feature firstFeature = findOwnedFeature(specDocumentId, userId, first);
        Feature secondFeature = findOwnedFeature(specDocumentId, userId, second);

        return first == featureId ? firstFeature : secondFeature;
    }

    private Feature requireOwnedFeature(Long specDocumentId, Long userId, Long featureId) {
        requireSpecDocument(specDocumentId, userId);
        // 대조 스냅샷을 읽고 저장하는 동안 요구사항이 교체되지 않게 문서부터 잠근다.
        specDocumentRepository.lockById(specDocumentId);
        return findOwnedFeature(specDocumentId, userId, featureId);
    }

    private Feature findOwnedFeature(Long specDocumentId, Long userId, Long featureId) {
        return featureRepository.findOwned(featureId, specDocumentId, userId)
                .orElseThrow(() -> {
                    log.warn(
                            "[기능 검토] 기능이 없거나 접근 권한이 없습니다. specDocumentId: {}, featureId: {}, userId: {}",
                            specDocumentId,
                            featureId,
                            userId
                    );
                    return new NotFoundException(ErrorCode.FEATURE_NOT_ACCESSIBLE);
                });
    }

    /**
     * 문서 단위 작업의 권한 확인.
     *
     * <p>없는 문서, 남의 문서, 삭제된 프로젝트의 문서가 모두 같은 404가 되어야 문서 id의
     * 존재 여부가 새지 않는다.
     */
    private void requireSpecDocument(Long specDocumentId, Long userId) {
        specDocumentRepository.findOwned(specDocumentId, userId)
                .orElseThrow(() -> {
                    log.warn(
                            "[기능 검토] 기능명세서가 없거나 접근 권한이 없습니다. specDocumentId: {}, userId: {}",
                            specDocumentId,
                            userId
                    );
                    return new NotFoundException(ErrorCode.FEATURE_SPEC_NOT_ACCESSIBLE);
                });
    }

    /** 페이지 정보는 비어 있을 수 있다. 한쪽만 있으면 있는 쪽을 쓴다. */
    private Integer smaller(Integer left, Integer right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.min(left, right);
    }

    private Integer larger(Integer left, Integer right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.max(left, right);
    }
}
