package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class SpecDocumentWriter {

    private final SpecDocumentRepository specDocumentRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    @Transactional
    SpecDocument save(Long projectId, Long userId, String fileName, String storageKey) {
        return specDocumentRepository.save(
                SpecDocument.builder()
                        .project(projectRepository.getReferenceById(projectId))
                        .user(userRepository.getReferenceById(userId))
                        .fileName(fileName)
                        .storageKey(storageKey)
                        .build()
        );
    }
}
