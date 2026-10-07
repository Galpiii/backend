package com.github.galpiii.galpi.domain.featurematch.service;

import com.github.galpiii.galpi.ai.dto.FeatureMatchingRequest;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.PrRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.ProjectRow;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchCurrentPullRequest;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchCurrentState;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchCurrentFeatureRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchCurrentPullRequestRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchCurrentStateRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchTargetRepository;
import com.github.galpiii.galpi.domain.featurematch.support.FeatureMatchInputAssembler;
import com.github.galpiii.galpi.domain.featurematch.support.FeatureMatchSnapshot;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeatureMatchChangeServiceTest {

    @Test
    @DisplayName("결과가 있는 PR 300건도 커밋·파일을 각각 한 번만 일괄 조회한다")
    void batchesPullRequestInputsForChanges() {
        long projectId = 1;
        long userId = 2;
        long documentId = 3;
        FeatureMatchQueryRepository queries = mock(FeatureMatchQueryRepository.class);
        FeatureMatchCurrentStateRepository states = mock(FeatureMatchCurrentStateRepository.class);
        FeatureMatchCurrentFeatureRepository features = mock(FeatureMatchCurrentFeatureRepository.class);
        FeatureMatchCurrentPullRequestRepository pullRequests = mock(FeatureMatchCurrentPullRequestRepository.class);
        FeatureMatchInputAssembler assembler = mock(FeatureMatchInputAssembler.class);
        FeatureMatchChangeService service = new FeatureMatchChangeService(queries, states, features,
                pullRequests, mock(FeatureMatchTargetRepository.class), assembler);
        FeatureMatchCurrentState state = mock(FeatureMatchCurrentState.class);
        SpecDocument document = mock(SpecDocument.class);
        when(document.getId()).thenReturn(documentId);
        when(state.getSpecDocument()).thenReturn(document);
        when(state.getFeatureSnapshotHash()).thenReturn(FeatureMatchSnapshot.featureHash(List.of(), List.of()));
        when(queries.project(projectId, userId, false)).thenReturn(new ProjectRow(projectId, userId, documentId));
        when(queries.features(documentId)).thenReturn(List.of());
        when(queries.requirements(documentId)).thenReturn(List.of());
        when(states.findById(projectId)).thenReturn(Optional.of(state));

        var input = new FeatureMatchingRequest.PullRequest(null, null, null, null, null, List.of(), List.of());
        when(assembler.pullRequest(any(), eq(10), anyList(), anyList())).thenReturn(input);
        List<PrRow> current = new ArrayList<>();
        List<FeatureMatchCurrentPullRequest> saved = new ArrayList<>();
        for (long id = 1; id <= 300; id++) {
            PrRow pr = mock(PrRow.class);
            when(pr.id()).thenReturn(id);
            when(pr.analysisStatus()).thenReturn(PullRequestAnalysisStatus.COMPLETED);
            when(pr.headSha()).thenReturn("head");
            when(pr.analysisHeadSha()).thenReturn("head");
            current.add(pr);
            FeatureMatchCurrentPullRequest previous = mock(FeatureMatchCurrentPullRequest.class);
            when(previous.getPullRequestId()).thenReturn(id);
            when(previous.getFeatureInputChars()).thenReturn(10);
            when(previous.getAnalysisInputChars()).thenReturn(0);
            when(previous.getSourceSnapshotHash()).thenReturn("source");
            when(previous.getAnalysisSnapshotHash()).thenReturn(FeatureMatchSnapshot.analysisHash(input));
            saved.add(previous);
        }
        when(queries.pullRequests(projectId)).thenReturn(current);
        when(pullRequests.findAllByProjectId(projectId)).thenReturn(saved);

        service.changes(projectId, userId);

        verify(queries, times(1)).commitInputs(anyList());
        verify(queries, times(1)).fileInputs(anyList());
        verify(assembler, never()).pullRequest(any(), anyInt());
        verify(assembler, never()).sourcePullRequest(any(), anyInt(), anyInt());
    }
}
