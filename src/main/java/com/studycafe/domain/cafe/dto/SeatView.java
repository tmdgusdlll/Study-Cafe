package com.studycafe.domain.cafe.dto;

// 좌석 하나. 빈자리면 occupant가 null
public record SeatView(int seatId, Occupant occupant) {
}
