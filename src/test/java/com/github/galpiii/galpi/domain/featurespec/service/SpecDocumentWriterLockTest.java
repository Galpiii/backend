package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("명세서 교체 잠금 순서")
class SpecDocumentWriterLockTest {
    @Test
    @DisplayName("문서 삭제보다 프로젝트 선점이 먼저 수행된다")
    void projectBeforeDocument() {
        SpecDocumentRepository documents = mock(SpecDocumentRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        UserRepository users = mock(UserRepository.class);
        when(projects.findOwnedForUpdate(1L, 2L)).thenReturn(Optional.of(mock(Project.class)));
        when(documents.deleteByIdReturningCount(3L)).thenReturn(0);

        SpecDocumentWriter writer = new SpecDocumentWriter(documents, projects, users);
        assertThatThrownBy(() -> writer.replace(1L, 2L, 3L, "spec.pdf"))
                .isInstanceOf(ConflictException.class);

        InOrder order = inOrder(projects, documents);
        order.verify(projects).findOwnedForUpdate(1L, 2L);
        order.verify(documents).deleteByIdReturningCount(3L);
    }
}
