package com.studycafe.domain.cafe.service;

import com.studycafe.domain.cafe.dto.SeatView;
import com.studycafe.domain.cafe.exception.CafeException;
import com.studycafe.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatRegistryTest {

    private final SeatRegistry registry = new SeatRegistry();
    private final Instant t0 = Instant.parse("2026-10-04T10:00:00Z");

    @Test
    void 처음에는_10개_좌석이_모두_비어있다() {
        assertThat(registry.snapshot()).hasSize(10)
                .allSatisfy(seat -> assertThat(seat.occupant()).isNull());
        assertThat(registry.snapshot()).extracting(SeatView::seatId)
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    void 빈자리에_앉으면_좌석표에_표시된다() {
        assertThat(registry.take(1L, "라떼", 3, t0)).isTrue();

        SeatView seat = registry.snapshot().get(3);
        assertThat(seat.occupant().memberId()).isEqualTo(1L);
        assertThat(seat.occupant().nickname()).isEqualTo("라떼");
        assertThat(seat.occupant().sittingSince()).isEqualTo(t0);
        assertThat(seat.occupant().studying()).isFalse();
    }

    @Test
    void 다른_회원이_앉은_자리는_SEAT_TAKEN() {
        registry.take(1L, "라떼", 3, t0);

        assertThatThrownBy(() -> registry.take(2L, "모카", 3, t0))
                .isInstanceOf(CafeException.class)
                .hasMessage(ErrorCode.SEAT_TAKEN.getMessage());
    }

    @Test
    void 범위_밖이거나_없는_좌석은_INVALID_SEAT() {
        for (Integer seatId : new Integer[]{-1, 10, null}) {
            assertThatThrownBy(() -> registry.take(1L, "라떼", seatId, t0))
                    .isInstanceOf(CafeException.class)
                    .hasMessage(ErrorCode.INVALID_SEAT.getMessage());
        }
    }

    @Test
    void 이미_앉은_자리를_다시_고르면_바뀌지_않는다() {
        registry.take(1L, "라떼", 3, t0);

        assertThat(registry.take(1L, "라떼", 3, t0.plusSeconds(60))).isFalse();
        assertThat(registry.snapshot().get(3).occupant().sittingSince()).isEqualTo(t0);
    }

    @Test
    void 자리를_옮기면_이전_자리는_비고_앉은_시각과_공부상태는_유지된다() {
        registry.take(1L, "라떼", 3, t0);
        registry.updateStudying(1L, true);

        assertThat(registry.take(1L, "라떼", 5, t0.plusSeconds(600))).isTrue();

        assertThat(registry.snapshot().get(3).occupant()).isNull();
        assertThat(registry.snapshot().get(5).occupant().sittingSince()).isEqualTo(t0);
        assertThat(registry.snapshot().get(5).occupant().studying()).isTrue();
    }

    @Test
    void 공부상태가_바뀔_때만_true() {
        registry.take(1L, "라떼", 3, t0);

        assertThat(registry.updateStudying(1L, true)).isTrue();
        assertThat(registry.updateStudying(1L, true)).isFalse();
        assertThat(registry.snapshot().get(3).occupant().studying()).isTrue();
    }

    @Test
    void 앉지_않은_회원의_공부상태는_무시한다() {
        assertThat(registry.updateStudying(9L, true)).isFalse();
    }

    @Test
    void 일어나면_자리가_빈다() {
        registry.take(1L, "라떼", 3, t0);

        assertThat(registry.leave(1L)).isTrue();
        assertThat(registry.leave(1L)).isFalse();
        assertThat(registry.snapshot().get(3).occupant()).isNull();
    }

    @Test
    void 같은_빈자리를_동시에_고르면_한_명만_앉는다() throws InterruptedException {
        int members = 20;
        ExecutorService pool = Executors.newFixedThreadPool(members);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger seated = new AtomicInteger();
        for (long id = 1; id <= members; id++) {
            long memberId = id;
            pool.submit(() -> {
                start.await();
                try {
                    if (registry.take(memberId, "손님" + memberId, 0, t0)) seated.incrementAndGet();
                } catch (CafeException ignored) {
                    // 자리를 놓친 회원
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(seated.get()).isEqualTo(1);
    }
}
