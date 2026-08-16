package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecUploadResponse;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecFileValidator;
import com.github.galpiii.galpi.domain.featurespec.validator.ValidatedFeatureSpec;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureSpecService {

    private final ProjectRepository projectRepository;
    private final FeatureSpecFileValidator featureSpecFileValidator;
    private final S3Service s3Service;
    private final SpecDocumentWriter specDocumentWriter;

    // 기능명세서 업로드
    public FeatureSpecUploadResponse upload(
            Long projectId,
            Long userId,
            MultipartFile file
    ) {
        verifyProjectOwner(projectId, userId);

        ValidatedFeatureSpec validatedFeatureSpec = featureSpecFileValidator.validate(file);

        try {
            String storageKey = s3Service.upload(validatedFeatureSpec.tempFile(), projectId);

            SpecDocument savedSpecDocument = saveOrDeleteStorageObject(
                    projectId,
                    userId,
                    validatedFeatureSpec.fileName(),
                    storageKey
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

    // 프로젝트 존재 및 소유자 검증
    private void verifyProjectOwner(Long projectId, Long userId) {
        if (!projectRepository.existsByIdAndUserId(projectId, userId)) {
            log.warn(
                    "[기능명세서 업로드] 프로젝트가 없거나 접근 권한이 없습니다. projectId: {}, userId: {}",
                    projectId,
                    userId
            );
            throw new NotFoundException(ErrorCode.PROJECT_NOT_ACCESSIBLE);
        }
    }

    // SpecDocument 저장. 실패하면 방금 올린 원본을 지운다.
    private SpecDocument saveOrDeleteStorageObject(
            Long projectId,
            Long userId,
            String fileName,
            String storageKey
    ) {
        try {
            return specDocumentWriter.save(projectId, userId, fileName, storageKey);
        } catch (RuntimeException e) {
            log.error(
                    "[기능명세서 업로드] 기능명세서 저장 실패. projectId: {}, storageKey: {}",
                    projectId,
                    storageKey,
                    e
            );
            deleteStorageObject(storageKey);

            throw new GlobalException(ErrorCode.FEATURE_SPEC_SAVE_FAILED);
        }
    }

    // DB 저장 실패 시 s3 객체도 삭제하도록
    private void deleteStorageObject(String storageKey) {
        try {
            s3Service.delete(storageKey);
            log.info("[기능명세서 업로드] 저장 실패로 원본을 삭제했습니다. storageKey: {}", storageKey);
        } catch (RuntimeException e) {
            log.error(
                    "[기능명세서 업로드] 보상 삭제 실패. 원본이 S3에 남습니다. storageKey: {}",
                    storageKey,
                    e
            );
        }
    }
}
