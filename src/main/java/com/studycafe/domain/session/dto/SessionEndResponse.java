package com.studycafe.domain.session.dto;

import com.studycafe.domain.session.entity.StudySession;

import java.time.LocalDateTime;

public record SessionEndResponse(
        Long sessionId,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        long durationMinutes,
        long earnedPoints
) {
    public static SessionEndResponse of(StudySession session, long durationMinutes, long earnedPoints) {
        return new SessionEndResponse(
                session.getId(),
                session.getStartedAt(),
                session.getEndedAt(),
                durationMinutes,
                earnedPoints
        );
    }
}
