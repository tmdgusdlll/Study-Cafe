package com.studycafe.domain.cafe.controller;

import com.studycafe.domain.cafe.dto.SeatError;
import com.studycafe.domain.cafe.dto.SeatView;
import com.studycafe.domain.cafe.dto.StatusRequest;
import com.studycafe.domain.cafe.dto.TakeSeatRequest;
import com.studycafe.domain.cafe.service.CafeSeatService;
import com.studycafe.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class CafeSeatController {

    private final CafeSeatService cafeSeatService;

    // 구독하자마자 현재 좌석표를 한 번 받는다
    @SubscribeMapping("/cafe/seats")
    public List<SeatView> seats() {
        return cafeSeatService.snapshot();
    }

    @MessageMapping("/cafe/seat.take")
    public void take(TakeSeatRequest request, Principal principal) {
        cafeSeatService.take(memberId(principal), request.seatId());
    }

    @MessageMapping("/cafe/status")
    public void status(StatusRequest request, Principal principal) {
        cafeSeatService.updateStudying(memberId(principal), request.studying());
    }

    @MessageExceptionHandler(CustomException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public SeatError handle(CustomException e) {
        return new SeatError(e.getErrorCode().name());
    }

    private static Long memberId(Principal principal) {
        return Long.valueOf(principal.getName());
    }
}
