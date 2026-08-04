package com.github.galpiii.galpi.domain.featurespec.entity;

import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "spec_documents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpecDocument extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_status", nullable = false, length = 20)
    private ExtractionStatus extractionStatus;

    @Builder
    private SpecDocument(Project project, User user, String fileName) {
        this.project = project;
        this.user = user;
        this.fileName = fileName;
        this.extractionStatus = ExtractionStatus.PENDING;
    }
}
