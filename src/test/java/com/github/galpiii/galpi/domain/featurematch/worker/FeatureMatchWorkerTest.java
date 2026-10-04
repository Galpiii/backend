package com.github.galpiii.galpi.domain.featurematch.worker;

import com.github.galpiii.galpi.domain.featurematch.config.FeatureMatchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FeatureMatchWorkerTest {

    private final FeatureMatchReconciler writer = mock(FeatureMatchReconciler.class);
    private final FeatureMatchClaimer claimer = mock(FeatureMatchClaimer.class);
    private final FeatureMatchRunExecutor executor = mock(FeatureMatchRunExecutor.class);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final List<Runnable> tasks = new ArrayList<>();
    private final List<Runnable> heartbeats = new ArrayList<>();
    private final List<ScheduledFuture<?>> heartbeatFutures = new ArrayList<>();
    private final List<String> tokens = new ArrayList<>();
    private final FeatureMatchProperties properties = new FeatureMatchProperties(
            true, Duration.ofSeconds(5), Duration.ofMinutes(10), Duration.ofSeconds(5), 3, 4, 120000);
    private FeatureMatchWorker worker;

    @BeforeEach
    void setUp() {
        worker = worker(tasks::add);
        when(claimer.claim(anyString())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            tokens.add(token);
            long id = tokens.size();
            return Optional.of(id);
        });
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    heartbeats.add(invocation.getArgument(0));
                    ScheduledFuture<?> future = mock(ScheduledFuture.class);
                    heartbeatFutures.add(future);
                    return future;
                });
        when(claimer.heartbeat(anyLong(), anyString())).thenReturn(true);
    }

    private FeatureMatchWorker worker(Executor taskExecutor) {
        return new FeatureMatchWorker(writer, claimer, executor, properties, taskExecutor, scheduler);
    }

    @Test
    @DisplayName("느린 대상을 기다리지 않고 빈 슬롯만 채운다")
    void refillsOnlyFreeSlotsWithoutWaitingForSlowTasks() {
        worker.runOnce();
        assertThat(tasks).hasSize(4);
        verifyNoInteractions(executor);
        worker.runOnce();
        assertThat(tasks).hasSize(4);

        // 1개만 완료시킨다. 나머지 3개의 실행을 기다리지 않고 다음 폴링이 1개를 채워야 한다.
        tasks.getFirst().run();
        verify(heartbeatFutures.getFirst()).cancel(false);
        worker.runOnce();

        assertThat(tasks).hasSize(5);
        assertThat(tokens).doesNotHaveDuplicates();
        verify(claimer, times(5)).claim(anyString());
        verify(executor).execute(1, tokens.getFirst());
    }

    @Test
    @DisplayName("대상별 토큰으로 lease를 갱신하고 개별 종료한다")
    void heartbeatUsesEachTargetsOwnTokenAndStopsIndependently() {
        worker.runOnce();
        for (int index = 0; index < heartbeats.size(); index++) {
            heartbeats.get(index).run();
            verify(claimer).heartbeat(index + 1, tokens.get(index));
        }
        tasks.getFirst().run();
        verify(heartbeatFutures.getFirst()).cancel(false);
        verify(heartbeatFutures.get(1), never()).cancel(false);
    }

    @Test
    @DisplayName("선점 상실 시 lease 갱신만 멈추고 호출 종료까지 슬롯을 유지한다")
    void lostLeaseStopsHeartbeatButKeepsSlotUntilCallActuallyEnds() {
        worker.runOnce();
        when(claimer.heartbeat(1, tokens.getFirst())).thenReturn(false);
        heartbeats.getFirst().run();
        verify(heartbeatFutures.getFirst()).cancel(false);
        worker.runOnce();
        assertThat(tasks).hasSize(4);
    }

    @Test
    @DisplayName("lease 갱신 실패가 다른 작업의 슬롯에 영향을 주지 않는다")
    void heartbeatFailureDoesNotStopOtherTasksOrReleaseTheirSlots() {
        worker.runOnce();
        when(claimer.heartbeat(1, tokens.getFirst())).thenThrow(new IllegalStateException("DB unavailable"));
        heartbeats.getFirst().run();
        verify(heartbeatFutures.getFirst(), never()).cancel(false);
        worker.runOnce();
        assertThat(tasks).hasSize(4);
    }

    @Test
    @DisplayName("실행 제출 거절은 선점과 슬롯을 반환한다")
    void rejectedSubmissionReleasesClaimAndSlot() {
        FeatureMatchWorker rejecting = worker(task -> {
            throw new RejectedExecutionException();
        });
        rejecting.runOnce();
        verify(claimer).release(1, tokens.getFirst());
        verify(heartbeatFutures.getFirst()).cancel(false);
        rejecting.runOnce();
        assertThat(tokens).hasSize(2);
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("lease 스케줄 등록 거절도 선점을 반환한다")
    void heartbeatSchedulingRejectionAlsoReturnsClaim() {
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new RejectedExecutionException());
        worker.runOnce();
        verify(claimer).release(1, tokens.getFirst());
        assertThat(tasks).isEmpty();
    }

    @Test
    @DisplayName("실행 오류가 나도 해당 슬롯을 해제한다")
    void executionFailureStillFreesItsSlot() {
        worker.runOnce();
        doThrow(new IllegalStateException()).when(executor).execute(1, tokens.getFirst());
        tasks.getFirst().run();
        worker.runOnce();
        assertThat(tasks).hasSize(5);
        verify(heartbeatFutures.getFirst()).cancel(false);
    }

    @Test
    @DisplayName("종료 시 lease 갱신과 새 선점을 중단한다")
    void shutdownStopsAllHeartbeatsAndNewClaims() {
        worker.runOnce();
        worker.stop();
        worker.runOnce();
        assertThat(tokens).hasSize(4);
        heartbeatFutures.forEach(future -> verify(future).cancel(false));
        verify(claimer, never()).release(anyLong(), anyString());
    }

    @Test
    @DisplayName("빈 큐는 실행 슬롯을 차지하지 않는다")
    void emptyQueueDoesNotConsumeCapacity() {
        when(claimer.claim(anyString())).thenReturn(Optional.empty());
        worker.runOnce();
        worker.runOnce();
        verify(claimer, times(2)).claim(anyString());
        verifyNoInteractions(executor, scheduler);
    }
}
