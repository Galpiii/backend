package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecStatusResponse;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionFailureCode;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("기능명세서 분석 상태 조회 — 실제 Postgres")
class FeatureSpecStatusIntegrationTest extends IntegrationTestSupport {

    private static final String FILE_NAME = "기능명세서.pdf";

    @Autowired
    private FeatureSpecService service;
    @Autowired
    private SpecDocumentRepository specDocumentRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;

    private User owner;
    private Project project;
    private SpecDocument specDocument;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(
                User.ofGithub(System.nanoTime(), "galpi-tester", "테스터", null, null));
        project = projectRepository.save(
                Project.create(owner, "갈피"));
        specDocument = specDocumentRepository.save(
                SpecDocument.builder().project(project).user(owner).fileName(FILE_NAME).build());
    }

    @Test
    @DisplayName("업로드 직후에는 PENDING이고 failureCode는 비어 있다")
    void returnsPendingRightAfterUpload() {
        FeatureSpecStatusResponse response =
                service.getStatus(project.getId(), specDocument.getId(), owner.getId());

        assertThat(response.specDocumentId()).isEqualTo(specDocument.getId());
        assertThat(response.extractionStatus()).isEqualTo(ExtractionStatus.PENDING);
        assertThat(response.failureCode()).isNull();
    }

    @Test
    @DisplayName("실패한 문서는 상태와 사유를 함께 돌려준다")
    void returnsFailureCodeWhenFailed() {
        ReflectionTestUtils.setField(specDocument, "extractionStatus", ExtractionStatus.FAILED);
        ReflectionTestUtils.setField(
                specDocument, "failureCode", ExtractionFailureCode.NO_FEATURE_EXTRACTED);
        specDocumentRepository.saveAndFlush(specDocument);

        FeatureSpecStatusResponse response =
                service.getStatus(project.getId(), specDocument.getId(), owner.getId());

        assertThat(response.extractionStatus()).isEqualTo(ExtractionStatus.FAILED);
        assertThat(response.failureCode()).isEqualTo(ExtractionFailureCode.NO_FEATURE_EXTRACTED);
    }

    @Test
    @DisplayName("타인 프로젝트의 문서는 조회할 수 없다")
    void rejectsProjectOwnedByAnotherUser() {
        User other = userRepository.save(
                User.ofGithub(System.nanoTime(), "other", "다른 사람", null, null));

        assertThatThrownBy(() ->
                service.getStatus(project.getId(), specDocument.getId(), other.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);
    }

    @Test
    @DisplayName("내 프로젝트 경로로 다른 프로젝트의 문서 id를 넣으면 거절한다")
    void rejectsSpecDocumentOfAnotherProject() {
        Project otherProject = projectRepository.save(
                Project.create(owner, "다른 프로젝트"));
        SpecDocument otherSpecDocument = specDocumentRepository.save(
                SpecDocument.builder().project(otherProject).user(owner).fileName(FILE_NAME).build());

        assertThatThrownBy(() ->
                service.getStatus(project.getId(), otherSpecDocument.getId(), owner.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_NOT_ACCESSIBLE);
    }

    @Test
    @DisplayName("없는 기능명세서는 거절한다")
    void rejectsMissingSpecDocument() {
        assertThatThrownBy(() ->
                service.getStatus(project.getId(), specDocument.getId() + 1000, owner.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_NOT_ACCESSIBLE);
    }
}
