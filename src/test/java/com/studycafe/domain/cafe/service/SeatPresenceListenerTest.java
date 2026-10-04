package com.studycafe.domain.cafe.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeatPresenceListenerTest {

    private final CafeSeatService cafeSeatService = mock(CafeSeatService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ScheduledFuture<?> future = mock(ScheduledFuture.class);
    private final SeatPresenceListener listener =
            new SeatPresenceListener(cafeSeatService, scheduler, Duration.ofSeconds(30));

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn((ScheduledFuture) future);
    }

    private Runnable scheduledTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(task.capture(), any(Instant.class));
        return task.getValue();
    }

    @Test
    void 마지막_연결이_끊기면_유예_후_자리를_비운다() {
        listener.connected(1L, "s1");
        listener.disconnected(1L, "s1");

        scheduledTask().run();

        verify(cafeSeatService).leave(1L);
    }

    @Test
    void 유예_안에_다시_연결하면_예약을_취소하고_자리를_유지한다() {
        listener.connected(1L, "s1");
        listener.disconnected(1L, "s1");
        Runnable task = scheduledTask();

        listener.connected(1L, "s2");
        verify(future).cancel(false);

        // 취소가 늦어 예약 작업이 이미 실행되어도 자리를 비우지 않는다
        task.run();
        verify(cafeSeatService, never()).leave(1L);
    }

    @Test
    void 탭_두_개_중_하나만_닫으면_예약하지_않는다() {
        listener.connected(1L, "s1");
        listener.connected(1L, "s2");
        listener.disconnected(1L, "s1");

        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void 같은_연결의_종료_이벤트가_두_번_와도_한_번만_처리한다() {
        listener.connected(1L, "s1");
        listener.connected(1L, "s2");
        listener.disconnected(1L, "s1");
        listener.disconnected(1L, "s1");

        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }
}
