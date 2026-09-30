package com.github.galpiii.galpi.domain.featurematch.service;

import org.junit.jupiter.api.DisplayName;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchRunCreatedResponse;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunStatus;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FeatureMatchRunServiceTest {

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
