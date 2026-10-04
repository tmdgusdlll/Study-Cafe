package com.studycafe.domain.cafe.service;

import com.studycafe.domain.cafe.exception.CafeException;
import com.studycafe.domain.member.entity.Member;
import com.studycafe.domain.member.exception.MemberException;
import com.studycafe.domain.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CafeSeatServiceTest {

    private final SeatRegistry registry = new SeatRegistry();
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final CafeSeatService service = new CafeSeatService(registry, memberRepository, messagingTemplate);

    @BeforeEach
    void setUp() {
        Member member = mock(Member.class);
        when(member.getNickname()).thenReturn("라떼");
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
    }

    @Test
    void 앉으면_닉네임과_함께_좌석표를_브로드캐스트한다() {
        service.take(1L, 3);

        assertThat(registry.snapshot().get(3).occupant().nickname()).isEqualTo("라떼");
        verify(messagingTemplate).convertAndSend(eq(CafeSeatService.TOPIC), any(Object.class));
    }

    @Test
    void 없는_회원은_앉을_수_없다() {
        when(memberRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.take(9L, 3)).isInstanceOf(MemberException.class);
        verify(messagingTemplate, never()).convertAndSend(eq(CafeSeatService.TOPIC), any(Object.class));
    }

    @Test
    void 이미_찬_자리는_예외이고_브로드캐스트하지_않는다() {
        registry.take(2L, "모카", 3, java.time.Instant.now());

        assertThatThrownBy(() -> service.take(1L, 3)).isInstanceOf(CafeException.class);
        verify(messagingTemplate, never()).convertAndSend(eq(CafeSeatService.TOPIC), any(Object.class));
    }

    @Test
    void 공부상태와_일어나기는_바뀐_경우에만_브로드캐스트한다() {
        service.take(1L, 3);
        service.updateStudying(1L, true);
        service.updateStudying(1L, true);
        service.leave(1L);
        service.leave(1L);

        // take 1 + studying 1 + leave 1
        verify(messagingTemplate, times(3)).convertAndSend(eq(CafeSeatService.TOPIC), any(Object.class));
    }
}
