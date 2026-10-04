package com.studycafe.domain.cafe.dto;

// 요청한 회원에게만 보내는 좌석 오류 (ErrorCode 이름)
public record SeatError(String code) {
}
