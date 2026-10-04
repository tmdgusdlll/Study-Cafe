package com.studycafe.domain.cafe.service;

import com.studycafe.domain.cafe.dto.Occupant;
import com.studycafe.domain.cafe.dto.SeatView;
import com.studycafe.domain.cafe.exception.CafeException;
import com.studycafe.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// 카페 좌석표 — 서버 메모리에만 둔다 (서버 1대 전제).
// ponytail: 서버를 여러 대로 늘리면 이 클래스의 저장소를 Redis로 바꾼다 (설계 문서 6절)
@Component
public class SeatRegistry {

    public static final int SEAT_COUNT = 10;

    private final Occupant[] seats = new Occupant[SEAT_COUNT];

    public synchronized List<SeatView> snapshot() {
        List<SeatView> views = new ArrayList<>(SEAT_COUNT);
        for (int i = 0; i < SEAT_COUNT; i++) {
            views.add(new SeatView(i, seats[i]));
        }
        return views;
    }

    // 앉기 또는 자리 이동. 이동해도 앉은 시각과 공부 상태는 유지한다
    public synchronized boolean take(Long memberId, String nickname, Integer seatId, Instant now) {
        if (seatId == null || seatId < 0 || seatId >= SEAT_COUNT) {
            throw new CafeException(ErrorCode.INVALID_SEAT);
        }
        Occupant target = seats[seatId];
        if (target != null) {
            if (target.memberId().equals(memberId)) return false;
            throw new CafeException(ErrorCode.SEAT_TAKEN);
        }
        int current = indexOf(memberId);
        if (current >= 0) {
            seats[seatId] = seats[current];
            seats[current] = null;
        } else {
            seats[seatId] = new Occupant(memberId, nickname, now, false);
        }
        return true;
    }

    public synchronized boolean updateStudying(Long memberId, boolean studying) {
        int current = indexOf(memberId);
        if (current < 0 || seats[current].studying() == studying) return false;
        seats[current] = seats[current].withStudying(studying);
        return true;
    }

    public synchronized boolean leave(Long memberId) {
        int current = indexOf(memberId);
        if (current < 0) return false;
        seats[current] = null;
        return true;
    }

    private int indexOf(Long memberId) {
        for (int i = 0; i < SEAT_COUNT; i++) {
            if (seats[i] != null && seats[i].memberId().equals(memberId)) return i;
        }
        return -1;
    }
}
