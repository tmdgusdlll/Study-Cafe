package com.studycafe.infra.jwt;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;

// STOMP CONNECT 프레임의 Authorization 헤더(JWT)를 검증해 연결 사용자(회원 ID)를 정한다
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtProvider jwtProvider;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) return message;

        String header = accessor.getFirstNativeHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        if (token == null || !jwtProvider.isValid(token)) {
            throw new MessagingException("UNAUTHORIZED");
        }
        Long memberId = jwtProvider.extractMemberId(token);
        accessor.setUser(new UsernamePasswordAuthenticationToken(memberId, null, List.of()));
        return message;
    }
}
