package com.github.galpiii.galpi.domain.analysis.entity;

import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.global.entity.BaseEntity;
import com.github.galpiii.galpi.global.persistence.StringListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 수집 범위 설정. {@code repository}가 비어 있으면 프로젝트 기본값이다.
 *
 * <p>Phase 1C에는 이 설정을 만드는 API가 없다. 행이 없으면 기본값으로 동작하고, 값이 필요한
 * 곳에서만 읽는다 — 설정 화면은 이 Phase의 범위가 아니다.
 */
@Entity
@Getter
@Table(
        name = "analysis_configs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_analysis_configs_project_repository",
                columnNames = {"project_id", "repository_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisConfig extends BaseEntity {

    /** ERD 기본값이자, 설정 행이 없을 때 쓰는 값. */
    public static final int DEFAULT_PR_LIMIT = 300;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id")
    private GithubRepository repository;

    @Column(nullable = false)
    private int prLimit;

    private OffsetDateTime prSince;

    @JdbcTypeCode(SqlTypes.JSON)
    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "jsonb")
    private List<String> includePaths;

    @JdbcTypeCode(SqlTypes.JSON)
    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "jsonb")
    private List<String> excludePaths;

    private AnalysisConfig(Project project, GithubRepository repository, int prLimit,
                           OffsetDateTime prSince, List<String> includePaths,
                           List<String> excludePaths) {
        this.project = project;
        this.repository = repository;
        this.prLimit = prLimit;
        this.prSince = prSince;
        this.includePaths = includePaths;
        this.excludePaths = excludePaths;
    }

    public static AnalysisConfig of(Project project, GithubRepository repository, int prLimit,
                                    OffsetDateTime prSince, List<String> includePaths,
                                    List<String> excludePaths) {
        return new AnalysisConfig(project, repository, prLimit, prSince, includePaths,
                excludePaths);
    }
}
