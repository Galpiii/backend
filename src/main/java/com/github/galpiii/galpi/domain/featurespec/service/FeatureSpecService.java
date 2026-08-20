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
        // 소유자 확인과 삭제 여부를 조회 조건에 함께 넣는다. 남의 프로젝트, 없는 프로젝트,
        // 삭제된 프로젝트가 모두 같은 404로 나가야 프로젝트 id의 존재 여부가 새지 않는다.
        Project project = projectRepository
                .findByIdAndOwnerIdAndDeletedAtIsNull(projectId, userId)
                .orElseThrow(() -> {
                    log.warn(
                            "[기능명세서 업로드] 프로젝트를 찾을 수 없습니다. projectId: {}, userId: {}",
                            projectId,
                            userId
                    );
                    return new NotFoundException(ErrorCode.PROJECT_NOT_FOUND);
                });

        User projectOwner = project.getOwner();

        String fileName = featureSpecFileValidator.validate(file);

        SpecDocument savedSpecDocument = specDocumentRepository.save(
                SpecDocument.builder()
                        .project(project)
                        .user(projectOwner)
                        .fileName(fileName)
                        .build()
        );

        // 프로젝트당 활성 문서는 하나다. 이전 문서 행은 지우지 않고 포인터만 옮긴다 —
        // 추출 결과가 매달려 있고, 다시 올리다 실패해도 과거 문서를 잃으면 안 된다.
        // 위저드도 이 시점에 ② 저장소 연결 단계로 넘어간다.
        project.attachSpecDocument(savedSpecDocument.getId());

        log.info(
                "[기능명세서 업로드] 업로드 완료. specDocumentId: {}, projectId: {}, userId: {}",
                savedSpecDocument.getId(),
                projectId,
                userId
        );

        return FeatureSpecUploadResponse.from(savedSpecDocument);
    }
}
