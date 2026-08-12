package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecUploadResponse;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecFileValidator;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.error.ErrorCode;
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

        featureSpecFileValidator.validate(file);

        SpecDocument savedSpecDocument = specDocumentRepository.save(
                SpecDocument.builder()
                        .project(project)
                        .user(projectOwner)
                        .fileName(file.getOriginalFilename())
                        .build()
        );

        log.info(
                "[기능명세서 업로드] 업로드 완료. specDocumentId: {}, projectId: {}, userId: {}",
                savedSpecDocument.getId(),
                projectId,
                userId
        );

        return FeatureSpecUploadResponse.from(savedSpecDocument);
    }
}
