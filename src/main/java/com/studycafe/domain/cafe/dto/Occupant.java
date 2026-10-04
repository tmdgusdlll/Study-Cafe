package com.studycafe.domain.cafe.dto;

import java.time.Instant;

// 좌석에 앉은 회원
public record Occupant(Long memberId, String nickname, Instant sittingSince, boolean studying) {

    public Occupant withStudying(boolean studying) {
        return new Occupant(memberId, nickname, sittingSince, studying);
    }
}
