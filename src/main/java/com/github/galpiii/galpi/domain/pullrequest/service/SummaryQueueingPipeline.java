package com.github.galpiii.galpi.domain.pullrequest.service;

import com.github.galpiii.galpi.domain.collection.pipeline.AnalysisPipelinePort;
import com.github.galpiii.galpi.domain.collection.pipeline.CollectedRepositorySnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 수집 결과를 요약 큐로 넘기는 실제 인계 대상.
 *
 * <p><b>여기서 하는 일은 행을 만드는 것뿐이다.</b> LLM을 부르지 않고, patch를 들고 나가지
 * 않는다. {@link CollectedRepositorySnapshot} 주석이 경고하는 대로 스냅샷은 이 호출이 끝날
 * 때까지만 유효하고 파일 참조가 가리키는 임시 디렉터리는 곧 지워진다 -- 스냅샷을 필드에
 * 담거나 다른 스레드로 넘기면 안 된다.
 *
 * <p>요약을 여기서 만들지 않는 이유가 이 Phase 설계의 핵심이다. 저장소 하나에 PR이 300개까지
 * 오는데 그만큼 LLM을 부르면 수집이 installation token 수명(1시간)과 {@code analysis_runs}
 * lease(30분)를 넘긴다. 게다가 실패한 PR만 다시 시도할 방법이 없어진다 -- 화면이 요구하는
 * "실패한 PR만 다시 분석"은 수집과 요약이 갈라져 있어야 성립한다.
 *
 * <p>{@link Primary}인 것은 기본 구현과의 관계 때문이다. {@code LoggingAnalysisPipeline}은
 * {@code @ConditionalOnMissingBean}이라 보통 조용히 물러나지만, 그 조건은 자동설정용이라
 * 컴포넌트 스캔 순서에 기대게 된다. 둘 다 등록되는 순간 주입이 모호해져 기동이 실패하므로
 * 순서에 관계없이 이쪽이 이기게 못박는다.
 *
 * <p>큐에 넣지 못하면 예외를 그대로 올린다. 삼키면 수집은 성공한 것으로 끝나는데 요약은
 * 영영 생기지 않고, 화면에는 "분석 대기"도 "분석 실패"도 아닌 빈 칸이 남는다. 저장소 하나가
 * 실패로 기록되고 사용자가 다시 실행하면 같은 PR이 다시 큐에 들어간다.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class SummaryQueueingPipeline implements AnalysisPipelinePort {

    private final PullRequestAnalysisWriter writer;

    @Override
    public void accept(CollectedRepositorySnapshot snapshot) {
        if (snapshot.pullRequests().isEmpty()) {
            return;
        }

        PullRequestAnalysisWriter.QueueResult result = writer.enqueue(
                snapshot.installationId(), snapshot.requestedBy(), snapshot.pullRequests());

        // 개수만 남긴다. 제목도 경로도 찍지 않는다 -- 비공개 저장소는 그것만으로도 내부가 드러난다.
        log.info("[요약] 인계 완료 repositoryId={} created={} requeued={} kept={}",
                snapshot.repositoryId(), result.created(), result.requeued(), result.kept());
    }
}
