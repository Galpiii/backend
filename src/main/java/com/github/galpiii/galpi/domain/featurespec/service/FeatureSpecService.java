package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecUploadResponse;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecFileValidator;
import com.github.galpiii.galpi.domain.featurespec.validator.ValidatedFeatureSpec;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureSpecService {

    private final ProjectRepository projectRepository;
    private final SpecDocumentRepository specDocumentRepository;
    private final FeatureSpecFileValidator featureSpecFileValidator;
    private final S3Service s3Service;

    // 기능명세서 업로드
    @Transactional
    public FeatureSpecUploadResponse upload(
            Long projectId,
            Long userId,
            MultipartFile file
    ) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> {
                    log.warn(
                            "[기능명세서 업로드] 프로젝트를 찾을 수 없습니다. projectId: {}",
                            projectId
                    );
                    return new NotFoundException(ErrorCode.PROJECT_NOT_FOUND);
                });

        User projectOwner = project.getUser();

        if (!projectOwner.getId().equals(userId)) {
            log.warn(
                    "[기능명세서 업로드] 타인 프로젝트 접근 시도. projectId: {}, userId: {}, ownerId: {}",
                    projectId,
                    userId,
                    projectOwner.getId()
            );
            throw new NotFoundException(ErrorCode.PROJECT_NOT_FOUND);
        }

        ValidatedFeatureSpec validatedFeatureSpec = featureSpecFileValidator.validate(file);

        // 검증에 쓴 임시 파일을 그대로 올린다. 어느 경로로 끝나든 finally에서 지운다.
        try {
            String storageKey = s3Service.upload(validatedFeatureSpec.tempFile(), projectId);
            deleteStorageObjectIfNotCommitted(storageKey);

            SpecDocument savedSpecDocument = specDocumentRepository.save(
                    SpecDocument.builder()
                            .project(project)
                            .user(projectOwner)
                            .fileName(validatedFeatureSpec.fileName())
                            .storageKey(storageKey)
                            .build()
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

    // s3 버킷에는 업로드 했으나 DB 저장에는 실패한 경우 s3 객체도 삭제
    private void deleteStorageObjectIfNotCommitted(String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn(
                    "[기능명세서 업로드] 트랜잭션이 없어 보상 삭제를 등록하지 못했습니다. storageKey: {}",
                    storageKey
            );
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }

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
        });
    }
}
