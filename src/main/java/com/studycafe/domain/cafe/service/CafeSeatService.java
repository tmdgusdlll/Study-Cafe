package com.studycafe.domain.cafe.service;

import com.studycafe.domain.cafe.dto.SeatView;
import com.studycafe.domain.member.exception.MemberException;
import com.studycafe.domain.member.repository.MemberRepository;
import com.studycafe.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

// 좌석 규칙(SeatRegistry)에 닉네임 조회와 브로드캐스트를 붙인다
@Service
@RequiredArgsConstructor
public class CafeSeatService {

    public static final String TOPIC = "/topic/cafe/seats";

    private final SeatRegistry seatRegistry;
    private final MemberRepository memberRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public List<SeatView> snapshot() {
        return seatRegistry.snapshot();
    }

    public void take(Long memberId, Integer seatId) {
        String nickname = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberException(ErrorCode.MEMBER_NOT_FOUND))
                .getNickname();
        if (seatRegistry.take(memberId, nickname, seatId, Instant.now())) broadcast();
    }

    public void updateStudying(Long memberId, boolean studying) {
        if (seatRegistry.updateStudying(memberId, studying)) broadcast();
    }

    public void leave(Long memberId) {
        if (seatRegistry.leave(memberId)) broadcast();
    }

    // 변경분이 아니라 항상 좌석 10개 전체를 보낸다
    private void broadcast() {
        messagingTemplate.convertAndSend(TOPIC, seatRegistry.snapshot());
    }
}
