package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecStatusResponse;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecUploadResponse;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecFileValidator;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecTempFileStore;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecFileValidator.ValidatedFeatureSpec;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureSpecService {

    private final ProjectRepository projectRepository;
    private final SpecDocumentRepository specDocumentRepository;
    private final FeatureSpecFileValidator featureSpecFileValidator;
    private final FeatureSpecTempFileStore tempFileStore;
    private final SpecDocumentWriter specDocumentWriter;
    private final FeatureExtractionService featureExtractionService;

    // 기능명세서 업로드 (llm 추출 시작)
    public FeatureSpecUploadResponse upload(
            Long projectId,
            Long userId,
            MultipartFile file
    ) {
        verifyProjectOwner(projectId, userId);
        verifyNoRegisteredSpecDocument(projectId);
        verifyExtractionCapacity(projectId);

        ValidatedFeatureSpec validatedFeatureSpec = featureSpecFileValidator.validate(file);

        SpecDocument savedSpecDocument = saveOrDeleteTempFile(projectId, userId, validatedFeatureSpec);

        submitExtraction(
                savedSpecDocument.getId(),
                validatedFeatureSpec.tempFile(),
                ErrorCode.FEATURE_SPEC_EXTRACTION_BUSY
        );

        // 위저드를 ② 저장소 연결 단계로 넘긴다. 제출이 거부되면 접수 자체를 되돌리는데
        // 단계 전진은 되돌릴 수단이 없으므로, 제출이 성공한 뒤에만 옮긴다.
        specDocumentWriter.attachToProject(projectId, savedSpecDocument.getId());

        log.info(
                "[기능명세서 업로드] 업로드 완료. specDocumentId: {}, projectId: {}, userId: {}",
                savedSpecDocument.getId(),
                projectId,
                userId
        );

        return FeatureSpecUploadResponse.from(savedSpecDocument);
    }

    /**
     * 기능명세서를 교체하고 다시 분석한다.
     *
     * <p>기존 추출 결과와 사용자의 검토 기록은 {@code ON DELETE CASCADE}로 함께 사라진다.
     * 새 명세서를 올린다는 것은 그것들을 버리기로 한 행동이므로 서버는 막지 않는다.
     */
    public FeatureSpecUploadResponse replace(
            Long projectId,
            Long userId,
            MultipartFile file
    ) {
        verifyProjectOwner(projectId, userId);
        SpecDocument current = findReplaceableSpecDocument(projectId);
        verifyExtractionCapacity(projectId);

        ValidatedFeatureSpec validatedFeatureSpec = featureSpecFileValidator.validate(file);
        verifyExtractionCapacityBeforeDeleting(projectId, validatedFeatureSpec);

        SpecDocument savedSpecDocument =
                replaceOrDeleteTempFile(projectId, userId, current.getId(), validatedFeatureSpec);

        submitExtraction(
                savedSpecDocument.getId(),
                validatedFeatureSpec.tempFile(),
                ErrorCode.FEATURE_SPEC_REPLACE_BUSY
        );

        log.info(
                "[기능명세서 교체] 교체 완료. specDocumentId: {}, 이전 specDocumentId: {}, projectId: {}, userId: {}",
                savedSpecDocument.getId(),
                current.getId(),
                projectId,
                userId
        );

        return FeatureSpecUploadResponse.from(savedSpecDocument);
    }

    /**
     * 교체할 수 있는 상태인지 확인한다.
     *
     * <p>분석 중인 문서를 지우면 워커가 결과를 저장할 자리를 잃고, 그때까지 쓴 OpenAI 비용도
     * 버려진다. 첫 요청이 커밋된 뒤 두 번째가 도착하는 순차적 더블클릭은 갓 만들어진 PENDING
     * 문서를 보고 여기서 걸린다.
     *
     * <p>동시에 도착한 더블클릭은 여기서 못 막는다. 이 확인과 실제 삭제 사이에 PDF 파싱이
     * 통째로 들어가 두 요청이 나란히 통과하기 때문이다. 늦은 쪽은 삭제 행 수가 0인 것으로
     * {@code SpecDocumentWriter.replace}에서 갈린다.
     *
     * <p>상태 전이는 워커만 하고 한 방향으로만 간다. COMPLETED·FAILED는 종착 상태라 다른
     * 요청이 끼어들지 않는 한 확인한 값이 그대로 유지되므로 별도의 잠금은 두지 않는다.
     */
    private SpecDocument findReplaceableSpecDocument(Long projectId) {
        SpecDocument current = specDocumentRepository.findByProjectId(projectId)
                .orElseThrow(() -> {
                    log.warn("[기능명세서 교체] 교체할 기능명세서가 없습니다. projectId: {}", projectId);
                    return new NotFoundException(ErrorCode.FEATURE_SPEC_NOT_ACCESSIBLE);
                });

        ExtractionStatus status = current.getExtractionStatus();

        if (status == ExtractionStatus.PENDING || status == ExtractionStatus.PROCESSING) {
            log.warn(
                    "[기능명세서 교체] 분석이 진행 중이라 교체할 수 없습니다. specDocumentId: {}, status: {}",
                    current.getId(),
                    status
            );
            throw new ConflictException(ErrorCode.FEATURE_SPEC_EXTRACTION_IN_PROGRESS);
        }

        return current;
    }

    private void verifyExtractionCapacityBeforeDeleting(
            Long projectId,
            ValidatedFeatureSpec validatedFeatureSpec
    ) {
        try {
            verifyExtractionCapacity(projectId);
        } catch (RuntimeException e) {
            tempFileStore.delete(validatedFeatureSpec.tempFile());
            throw e;
        }
    }

    private SpecDocument replaceOrDeleteTempFile(
            Long projectId,
            Long userId,
            Long currentSpecDocumentId,
            ValidatedFeatureSpec validatedFeatureSpec
    ) {
        try {
            return specDocumentWriter.replace(
                    projectId, userId, currentSpecDocumentId, validatedFeatureSpec.fileName());
        } catch (RuntimeException e) {
            tempFileStore.delete(validatedFeatureSpec.tempFile());
            throw e;
        }
    }

    private SpecDocument saveOrDeleteTempFile(
            Long projectId,
            Long userId,
            ValidatedFeatureSpec validatedFeatureSpec
    ) {
        try {
            return specDocumentWriter.save(projectId, userId, validatedFeatureSpec.fileName());
        } catch (RuntimeException e) {
            tempFileStore.delete(validatedFeatureSpec.tempFile());
            throw e;
        }
    }

    // 제출이 거부되면 비동기 메서드가 실행되지 않아 임시 파일도 접수한 행도 정리할 주체가 없다.
    // 분석을 시작조차 못 했으므로 접수 자체를 되돌린다.
    // 거부 시 안내가 업로드와 교체에서 다르다. 교체는 기존 문서를 이미 지운 뒤라 되돌릴 원본이 없다.
    private void submitExtraction(Long specDocumentId, File tempFile, ErrorCode rejectedError) {
        try {
            featureExtractionService.extract(specDocumentId, tempFile);
        } catch (TaskRejectedException e) {
            log.error(
                    "[기능명세서] 분석 작업이 거부되었습니다. specDocumentId: {}",
                    specDocumentId,
                    e
            );
            specDocumentWriter.delete(specDocumentId);
            tempFileStore.delete(tempFile);

            throw new GlobalException(rejectedError);
        }
    }

    // 기능명세서 분석 상태 조회
    @Transactional(readOnly = true)
    public FeatureSpecStatusResponse getStatus(Long specDocumentId, Long userId) {
        SpecDocument specDocument = specDocumentRepository
                .findOwned(specDocumentId, userId)
                .orElseThrow(() -> {
                    log.warn(
                            "[기능명세서 상태 조회] 기능명세서가 없거나 접근 권한이 없습니다. specDocumentId: {}, userId: {}",
                            specDocumentId,
                            userId
                    );
                    return new NotFoundException(ErrorCode.FEATURE_SPEC_NOT_ACCESSIBLE);
                });

        return FeatureSpecStatusResponse.from(specDocument);
    }

    // 프로젝트 존재 및 소유자 검증
    //
    // 소유자 확인과 삭제 여부를 조회 조건에 함께 넣는다. 남의 프로젝트, 없는 프로젝트,
    // 삭제된 프로젝트가 모두 같은 404로 나가야 프로젝트 id의 존재 여부가 새지 않는다.
    private void verifyProjectOwner(Long projectId, Long userId) {
        if (projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, userId).isEmpty()) {
            log.warn(
                    "[기능명세서] 프로젝트가 없거나 접근 권한이 없습니다. projectId: {}, userId: {}",
                    projectId,
                    userId
            );
            throw new NotFoundException(ErrorCode.PROJECT_NOT_FOUND);
        }
    }

    // 어차피 거절할 요청에 임시 파일 쓰기와 PDF 파싱 비용을 들이지 않는다.
    private void verifyExtractionCapacity(Long projectId) {
        if (featureExtractionService.isBusy()) {
            log.warn("[기능명세서] 분석 큐가 가득 차 요청을 거절합니다. projectId: {}", projectId);
            throw new GlobalException(ErrorCode.FEATURE_SPEC_EXTRACTION_BUSY);
        }
    }

    // 기능명세서 중복 등록 검증
    private void verifyNoRegisteredSpecDocument(Long projectId) {
        if (specDocumentRepository.existsByProjectId(projectId)) {
            log.warn(
                    "[기능명세서 업로드] 이미 등록된 기능명세서가 있습니다. projectId: {}",
                    projectId
            );
            throw new ConflictException(ErrorCode.FEATURE_SPEC_ALREADY_EXISTS);
        }
    }
}
