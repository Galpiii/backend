package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecUploadResponse;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecFileValidator;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
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
    private final UserRepository userRepository;
    private final SpecDocumentRepository specDocumentRepository;
    private final FeatureSpecFileValidator featureSpecFileValidator;

    // 기능명세서 업로드
    @Transactional
    public FeatureSpecUploadResponse upload(
            Long projectId,
            Long userId,
            MultipartFile file
    ) {
        log.info("[기능명세서 업로드] 업로드 시도.");

        // 추후 리팩토링 예정
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> {
                    log.warn(
                            "[기능명세서 업로드] 프로젝트를 찾을 수 없습니다. projectId: {}",
                            projectId
                    );
                    return new NotFoundException();
                });

        // 추후 리팩토링 예정
        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn(
                            "[기능명세서 업로드] 유저를 찾을 수 없습니다.. userId: {}",
                            userId
                    );
                    return new NotFoundException();
                });

        featureSpecFileValidator.validate(file);

        SpecDocument specDocument = SpecDocument.builder()
                .project(project)
                .user(user)
                .fileName(file.getOriginalFilename())
                .build();

        SpecDocument savedSpecDocument = specDocumentRepository.save(specDocument);

        log.info(
                "[기능명세서 업로드] 업로드 완료. specDocumentId: {}, projectId: {}, userId: {}",
                savedSpecDocument.getId(),
                projectId,
                userId
        );

        return FeatureSpecUploadResponse.from(savedSpecDocument);
    }
}
