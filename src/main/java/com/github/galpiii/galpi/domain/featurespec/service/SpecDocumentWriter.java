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
    SpecDocument save(Long projectId, Long userId, String fileName) {
        return specDocumentRepository.save(
                SpecDocument.builder()
                        .project(projectRepository.getReferenceById(projectId))
                        .user(userRepository.getReferenceById(userId))
                        .fileName(fileName)
                        .build()
        );
    }

    /**
     * 업로드 접수를 되돌린다.
     *
     * <p>분석 제출이 거부되면 분석을 시작조차 못 한 것이므로 행을 남기지 않는다. FAILED로 두면
     * 사용자는 specDocumentId를 받지 못한 채 중복 등록 검증에 걸려 다시 올릴 수도 없다.
     */
    @Transactional
    void delete(Long specDocumentId) {
        specDocumentRepository.deleteById(specDocumentId);
    }
}
