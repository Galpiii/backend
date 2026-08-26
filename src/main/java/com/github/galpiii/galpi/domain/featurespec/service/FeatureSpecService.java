package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecStatusResponse;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecUploadResponse;
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

        submitExtraction(savedSpecDocument.getId(), validatedFeatureSpec.tempFile());

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
    private void submitExtraction(Long specDocumentId, File tempFile) {
        try {
            featureExtractionService.extract(specDocumentId, tempFile);
        } catch (TaskRejectedException e) {
            log.error(
                    "[기능명세서 업로드] 분석 작업이 거부되었습니다. specDocumentId: {}",
                    specDocumentId,
                    e
            );
            specDocumentWriter.delete(specDocumentId);
            tempFileStore.delete(tempFile);

            throw new GlobalException(ErrorCode.FEATURE_SPEC_EXTRACTION_BUSY);
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
            log.warn("[기능명세서 업로드] 분석 큐가 가득 차 업로드를 거절합니다. projectId: {}", projectId);
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
