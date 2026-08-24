package com.github.galpiii.galpi.domain.analysis.service;

import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunRepository;
import com.github.galpiii.galpi.domain.github.event.GithubDisconnectedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * GitHub 연결이 끊기면 진행 중인 분석을 즉시 취소한다.
 *
 * <p>워커는 작업 생성 시점에 고정된 installation token으로 돌기 때문에, 사용자의 user token을
 * 지운다고 수집이 멈추지 않는다. 접근 권한을 근거로 시작된 작업이 근거가 사라진 뒤에도
 * 계속 도는 것을 막는 것은 이 취소뿐이다.
 *
 * <p>비동기로 미루지 않는다. 실패하면 연결 해제도 함께 실패하는 편이, 권한 없이 수집이
 * 계속되는 상태를 조용히 남기는 것보다 낫다.
 *
 * <p>그래서 발행자의 트랜잭션에 <b>반드시</b> 참여한다({@code MANDATORY}). 자기 트랜잭션을
 * 열면 토큰 삭제와 연결 상태 변경은 이미 커밋된 뒤라, 이 취소만 실패했을 때 되돌릴 것이
 * 없어진다. 트랜잭션 없이 발행되면 조용히 반쪽으로 도는 대신 여기서 터진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisRunDisconnectCanceller {

    private final AnalysisRunRepository runRepository;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onDisconnected(GithubDisconnectedEvent event) {
        int cancelled = runRepository.cancelInFlightByOwner(event.userId(), OffsetDateTime.now());
        if (cancelled > 0) {
            log.info("[분석] GitHub 연결 해제로 진행 중인 작업을 취소 userId={} count={}",
                    event.userId(), cancelled);
        }
    }
}
