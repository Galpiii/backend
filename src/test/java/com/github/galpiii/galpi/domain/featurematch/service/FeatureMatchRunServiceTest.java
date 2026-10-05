package com.github.galpiii.galpi.domain.featurematch.service;

import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchRunCreatedResponse;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunStatus;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.Counts;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.ProjectRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.RunRow;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchFailureCode;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FeatureMatchRunServiceTest {

    private final FeatureMatchQueryRepository queries = mock(FeatureMatchQueryRepository.class);
    private final FeatureMatchRunService service = new FeatureMatchRunService(queries, mock(FeatureMatchRunCreator.class));

    @ParameterizedTest
    @EnumSource(FeatureMatchRunStatus.class)
    @DisplayName("모든 상태의 최신 실행을 원래 명세서와 함께 복구한다")
    void restoresLatestRunRegardlessOfStatusOrActiveDocument(FeatureMatchRunStatus status) {
        var now = OffsetDateTime.now();
        var failure = status == FeatureMatchRunStatus.FAILED ? FeatureMatchFailureCode.AI_RESPONSE_INVALID : null;
        var run = new RunRow(123, 3, 10, 7, status, "hash", 2, 20, 0, 0,
                failure, now, null, now.minusMinutes(1));
        when(queries.project(3, 7, false)).thenReturn(new ProjectRow(3, 7, 99L));
        when(queries.latest(3)).thenReturn(run);
        when(queries.run(123)).thenReturn(run);
        when(queries.counts(123)).thenReturn(new Counts(10, 1, 6, 2, 1));

        var response = service.latest(3, 7);

        assertThat(response.featureMatchRunId()).isEqualTo(123);
        assertThat(response.specDocumentId()).isEqualTo(10);
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.progressPercent()).isEqualTo(45);
        assertThat(response.failureCode()).isEqualTo(failure);
        assertThat(response.createdAt()).isEqualTo(run.createdAt());
        assertThat(response.startedAt()).isEqualTo(run.startedAt());
        assertThat(response.finishedAt()).isNull();
        assertThat(response).isEqualTo(service.status(123, 7));
    }

    @Test
    @DisplayName("접근 가능한 프로젝트에 실행이 없으면 실행 없음 오류를 반환한다")
    void rejectsMissingRun() {
        when(queries.project(3, 7, false)).thenReturn(new ProjectRow(3, 7, null));
        assertThatThrownBy(() -> service.latest(3, 7)).isInstanceOfSatisfying(NotFoundException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FEATURE_MATCH_RUN_NOT_FOUND));
    }

    @Test
    @DisplayName("접근 불가 프로젝트는 실행 조회 전에 거부한다")
    void rejectsInaccessibleProjectBeforeLookingUpRun() {
        assertThatThrownBy(() -> service.latest(3, 7)).isInstanceOfSatisfying(NotFoundException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROJECT_NOT_FOUND));
        verify(queries).project(3, 7, false);
        verifyNoMoreInteractions(queries);
    }


    @Test
    @DisplayName("실행 생성은 Creator 단일 경로에 위임한다")
    void delegatesCreationToCreatorWithoutDuplicatingPersistence() {
        FeatureMatchQueryRepository queryRepository = mock(FeatureMatchQueryRepository.class);
        FeatureMatchRunCreator creator = mock(FeatureMatchRunCreator.class);
        FeatureMatchRunService service = new FeatureMatchRunService(queryRepository, creator);
        FeatureMatchRunCreatedResponse response = new FeatureMatchRunCreatedResponse(
                3, FeatureMatchRunStatus.QUEUED, 2, 1, 0, 1, 0, 0, OffsetDateTime.now());
        when(creator.create(15, 1)).thenReturn(response);

        assertThat(service.create(15, 1)).isSameAs(response);
        verify(creator).create(15, 1);
        verifyNoInteractions(queryRepository);
    }
}
