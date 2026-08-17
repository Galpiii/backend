package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecStatusResponse;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecUploadResponse;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecFileValidator;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecFileValidator.ValidatedFeatureSpec;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureSpecService {

    private final ProjectRepository projectRepository;
    private final SpecDocumentRepository specDocumentRepository;
    private final FeatureSpecFileValidator featureSpecFileValidator;
    private final SpecDocumentWriter specDocumentWriter;

    // 기능명세서 업로드
    public FeatureSpecUploadResponse upload(
            Long projectId,
            Long userId,
            MultipartFile file
    ) {
        verifyProjectOwner(projectId, userId);
        verifyNoRegisteredSpecDocument(projectId);

        ValidatedFeatureSpec validatedFeatureSpec = featureSpecFileValidator.validate(file);

        try {
            SpecDocument savedSpecDocument = specDocumentWriter.save(
                    projectId,
                    userId,
                    validatedFeatureSpec.fileName()
            );

            log.info(
                    "[기능명세서 업로드] 업로드 완료. specDocumentId: {}, projectId: {}, userId: {}",
                    savedSpecDocument.getId(),
                    projectId,
                    userId
            );

            return FeatureSpecUploadResponse.from(savedSpecDocument);
        } finally {
            featureSpecFileValidator.deleteTempFile(validatedFeatureSpec.tempFile());
        }
    }

    // 기능명세서 분석 상태 조회
    @Transactional(readOnly = true)
    public FeatureSpecStatusResponse getStatus(Long projectId, Long specDocumentId, Long userId) {
        verifyProjectOwner(projectId, userId);

        SpecDocument specDocument = specDocumentRepository
                .findByIdAndProjectId(specDocumentId, projectId)
                .orElseThrow(() -> {
                    log.warn(
                            "[기능명세서 상태 조회] 기능명세서를 찾을 수 없습니다. projectId: {}, specDocumentId: {}",
                            projectId,
                            specDocumentId
                    );
                    return new NotFoundException(ErrorCode.FEATURE_SPEC_NOT_ACCESSIBLE);
                });

        return FeatureSpecStatusResponse.from(specDocument);
    }

    // 프로젝트 존재 및 소유자 검증
    private void verifyProjectOwner(Long projectId, Long userId) {
        if (!projectRepository.existsByIdAndUserId(projectId, userId)) {
            log.warn(
                    "[기능명세서] 프로젝트가 없거나 접근 권한이 없습니다. projectId: {}, userId: {}",
                    projectId,
                    userId
            );
            throw new NotFoundException(ErrorCode.PROJECT_NOT_ACCESSIBLE);
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
