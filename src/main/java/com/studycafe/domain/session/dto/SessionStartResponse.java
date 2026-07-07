package com.studycafe.domain.session.dto;

import com.studycafe.domain.session.entity.StudySession;

import java.time.LocalDateTime;

public record SessionStartResponse(Long sessionId, LocalDateTime startedAt) {

    public static SessionStartResponse from(StudySession session) {
        return new SessionStartResponse(session.getId(), session.getStartedAt());
    }
}
