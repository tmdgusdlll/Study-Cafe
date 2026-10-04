package com.studycafe.domain.cafe.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.AbstractSubProtocolEvent;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

// 회원별 STOMP 연결을 세고, 마지막 연결이 끊기면 유예 시간 뒤 자리를 비운다
@Component
public class SeatPresenceListener {

    private final CafeSeatService cafeSeatService;
    private final TaskScheduler scheduler;
    private final Duration gracePeriod;

    // 회원 ID → 열린 STOMP 세션 ID들 (탭 여러 개, 종료 이벤트 중복 대응)
    private final Map<Long, Set<String>> sessions = new HashMap<>();
    private final Map<Long, ScheduledFuture<?>> pending = new HashMap<>();

    public SeatPresenceListener(CafeSeatService cafeSeatService,
                                @Qualifier("seatTaskScheduler") TaskScheduler scheduler,
                                @Value("${cafe.seat.grace-period}") Duration gracePeriod) {
        this.cafeSeatService = cafeSeatService;
        this.scheduler = scheduler;
        this.gracePeriod = gracePeriod;
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        Long memberId = memberId(event);
        if (memberId != null) connected(memberId, sessionId(event));
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        Long memberId = memberId(event);
        if (memberId != null) disconnected(memberId, event.getSessionId());
    }

    public synchronized void connected(Long memberId, String sessionId) {
        sessions.computeIfAbsent(memberId, id -> new HashSet<>()).add(sessionId);
        ScheduledFuture<?> scheduled = pending.remove(memberId);
        if (scheduled != null) scheduled.cancel(false);
    }

    public synchronized void disconnected(Long memberId, String sessionId) {
        Set<String> open = sessions.get(memberId);
        if (open == null || !open.remove(sessionId) || !open.isEmpty()) return;
        sessions.remove(memberId);
        pending.put(memberId, scheduler.schedule(() -> expire(memberId), Instant.now().plus(gracePeriod)));
    }

    private synchronized void expire(Long memberId) {
        pending.remove(memberId);
        // 유예 중 다시 연결했다면 그대로 둔다
        if (sessions.containsKey(memberId)) return;
        cafeSeatService.leave(memberId);
    }

    private static Long memberId(AbstractSubProtocolEvent event) {
        Principal user = event.getUser();
        return user == null ? null : Long.valueOf(user.getName());
    }

    private static String sessionId(AbstractSubProtocolEvent event) {
        return SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
    }
}
