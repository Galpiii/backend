package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class SpecDocumentWriter {

    /** V5 마이그레이션의 제약 이름. 바꾸면 409가 조용히 500으로 돌아간다. */
    private static final String UNIQUE_SPEC_DOCUMENTS_PROJECT = "uk_spec_documents_project";

    private final SpecDocumentRepository specDocumentRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    /**
     * 업로드를 접수한다.
     *
     * <p>호출 쪽이 이미 등록된 기능명세서가 없는지 보고 오지만, 그 확인과 여기 INSERT 사이에는
     * 경계가 없다. 같은 프로젝트로 두 요청이 동시에 들어오면 둘 다 확인을 통과해 문서가 두 개가
     * 되고, 분석이 두 번 돌아 OpenAI 비용도 두 배가 된다. 마지막은 DB가 막는다.
     */
    @Transactional
    public SpecDocument save(Long projectId, Long userId, String fileName) {
        try {
            return specDocumentRepository.saveAndFlush(
                    SpecDocument.builder()
                            .project(projectRepository.getReferenceById(projectId))
                            .user(userRepository.getReferenceById(userId))
                            .fileName(fileName)
                            .build()
            );
        } catch (DataIntegrityViolationException e) {
            if (!isAlreadyRegistered(e)) {
                throw e;
            }

            log.info("[기능명세서 업로드] 업로드가 동시에 들어와 UNIQUE 제약에서 갈렸습니다. projectId: {}", projectId);
            throw new ConflictException(ErrorCode.FEATURE_SPEC_ALREADY_EXISTS);
        }
    }

    /**
     * 활성 명세서 포인터를 옮기고 위저드를 ② 저장소 연결 단계로 넘긴다.
     *
     * <p>접수와 같은 트랜잭션에 넣지 않는다. 분석 제출이 거부되면 {@link #delete}로 접수를
     * 취소하는데, 단계 전진은 앞으로만 움직여 되돌릴 수단이 없다. 제출이 성공한 뒤에 옮겨야
     * 명세서가 없는 프로젝트가 ② 단계에 멈춰 서는 일이 없다.
     *
     * <p>{@code upload()}는 비동기 추출을 걸기 위해 트랜잭션 밖에 있다. 거기서 엔티티를
     * 고치면 영속성 컨텍스트가 없어 flush될 자리가 없으므로 여기서 경계를 연다.
     */
    @Transactional
    public void attachToProject(Long projectId, Long specDocumentId) {
        projectRepository.getReferenceById(projectId).attachSpecDocument(specDocumentId);
    }

    /**
     * 업로드 접수를 되돌린다.
     *
     * <p>분석 제출이 거부되면 분석을 시작조차 못 한 것이므로 행을 남기지 않는다. FAILED로 두면
     * 사용자는 specDocumentId를 받지 못한 채 중복 등록 검증에 걸려 다시 올릴 수도 없다.
     */
    @Transactional
    public void delete(Long specDocumentId) {
        specDocumentRepository.deleteById(specDocumentId);
    }

    /**
     * 제약 이름으로만 판단한다. 드라이버 메시지 문구는 버전에 따라 달라진다.
     *
     * <p>여기서 나올 수 있는 무결성 위반은 이것 말고도 프로젝트 동시 삭제로 인한 FK 위반이나
     * 앞으로 늘어날 제약이 있다. 그것까지 "이미 등록됨"으로 바꾸면 서버 결함이 사용자 실수로
     * 둔갑해 조용히 묻힌다. 나머지는 그대로 올려보내 500으로 드러내는 것이 맞다.
     */
    private static boolean isAlreadyRegistered(DataIntegrityViolationException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return UNIQUE_SPEC_DOCUMENTS_PROJECT.equalsIgnoreCase(violation.getConstraintName());
            }
        }

        return false;
    }
}
