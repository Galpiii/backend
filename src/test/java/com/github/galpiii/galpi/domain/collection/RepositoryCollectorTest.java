package com.github.galpiii.galpi.domain.collection;

import com.github.galpiii.galpi.domain.collection.archive.DownloadedArchive;
import com.github.galpiii.galpi.domain.collection.archive.ExtractedRepository;
import com.github.galpiii.galpi.domain.collection.archive.TarballDownloader;
import com.github.galpiii.galpi.domain.collection.archive.TarballExtractor;
import com.github.galpiii.galpi.domain.collection.file.FileSelectionResult;
import com.github.galpiii.galpi.domain.collection.file.RepositoryFileSelector;
import com.github.galpiii.galpi.domain.collection.pipeline.AnalysisPipelinePort;
import com.github.galpiii.galpi.domain.collection.pr.PullRequestCollector;
import com.github.galpiii.galpi.domain.github.client.GithubCollectionClient;
import com.github.galpiii.galpi.domain.github.client.dto.GithubRepositoryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 수집이 코드를 외부로 넘기기 직전에 무엇을 확인하는지.
 *
 * <p>저장소 하나를 수집하는 데 몇 분이 걸린다. 그 사이에 연결이 끊기거나 작업이 취소되면
 * 이미 받아 둔 데이터를 버려야지, 근거가 사라진 데이터를 파이프라인으로 흘려보내면 안 된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RepositoryCollector — 인계 직전 취소 확인")
class RepositoryCollectorTest {

    private static final String TOKEN = "ghs_token";

    @Mock
    private GithubCollectionClient client;
    @Mock
    private TarballDownloader downloader;
    @Mock
    private TarballExtractor extractor;
    @Mock
    private RepositoryFileSelector fileSelector;
    @Mock
    private PullRequestCollector pullRequestCollector;
    @Mock
    private AnalysisPipelinePort pipeline;

    private RepositoryCollector collector;

    @BeforeEach
    void setUp() {
        collector = new RepositoryCollector(client, downloader, extractor, fileSelector,
                pullRequestCollector, pipeline);

        given(client.getRepository(anyString(), anyString(), anyString())).willReturn(repository());
        given(client.getCommitSha(anyString(), anyString(), anyString(), anyString()))
                .willReturn("abc1234");
        given(pullRequestCollector.collect(anyString(), anyString(), anyString(), anyLong(),
                anyInt(), any()))
                .willReturn(new PullRequestCollector.PullRequestCollectionResult(
                        List.of(), 0, List.of()));
        // 임시 파일은 만들지 않는다. 없는 경로를 닫는 것은 두 record 모두 견딘다.
        given(downloader.download(anyString(), anyString(), anyString(), anyString()))
                .willReturn(new DownloadedArchive(Path.of("/tmp/galpi-does-not-exist.tar.gz"), 0));
        given(extractor.extract(any())).willReturn(new ExtractedRepository(
                null, null, 0, 0, List.of(), List.of()));
        given(fileSelector.select(any(), any(), any())).willReturn(
                new FileSelectionResult(List.of(), List.of(), List.of(), List.of(), 0));
    }

    @Test
    @DisplayName("관문이 막으면 파이프라인으로 넘기지 않는다")
    void doesNotHandOffWhenGuardBlocks() {
        assertThatThrownBy(() -> collector.collect(request(() -> {
            throw new CollectionAbandonedException();
        }))).isInstanceOf(CollectionAbandonedException.class);

        verify(pipeline, never()).accept(any());
    }

    @Test
    @DisplayName("관문이 막은 이유를 수집이 해석하지 않는다 — 예외를 그대로 흘려보낸다")
    void propagatesTheGuardsOwnReason() {
        assertThatThrownBy(() -> collector.collect(request(() -> {
            throw new IllegalStateException("동의가 없다");
        }))).isInstanceOf(IllegalStateException.class).hasMessage("동의가 없다");

        verify(pipeline, never()).accept(any());
    }

    @Test
    @DisplayName("관문을 통과하면 그대로 넘긴다")
    void handsOffWhenGuardPasses() {
        collector.collect(request(() -> {
        }));

        verify(pipeline).accept(any());
    }

    @Test
    @DisplayName("관문은 수집이 끝난 뒤에 묻는다 — 시작 시점의 판단으로는 몇 분의 공백을 덮지 못한다")
    void asksAfterCollectionFinishes() {
        boolean[] downloaded = {false};
        given(downloader.download(anyString(), anyString(), anyString(), anyString()))
                .willAnswer(invocation -> {
                    downloaded[0] = true;
                    return new DownloadedArchive(Path.of("/tmp/galpi-does-not-exist.tar.gz"), 0);
                });

        assertThatThrownBy(() -> collector.collect(request(() -> {
            // 다운로드가 끝난 뒤에 물어야 이 값이 true다.
            if (!downloaded[0]) {
                throw new AssertionError("수집을 시작하기도 전에 관문을 물었다");
            }
            throw new CollectionAbandonedException();
        }))).isInstanceOf(CollectionAbandonedException.class);

        verify(pipeline, never()).accept(any());
    }

    private static RepositoryCollector.CollectionRequest request(HandoffGuard handoffGuard) {
        return new RepositoryCollector.CollectionRequest(TOKEN, "galpiii", "backend",
                1L, 555L, 10, null, List.of(), List.of(), handoffGuard);
    }

    private static GithubRepositoryResponse repository() {
        return new GithubRepositoryResponse(555L, "backend", "galpiii/backend", null, true,
                "main", "https://github.com/galpiii/backend", Map.of(), null, null, null);
    }
}
