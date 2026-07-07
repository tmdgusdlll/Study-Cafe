package com.studycafe.domain.session.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(name = "study_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudySession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status;

    public static StudySession start(Long memberId) {
        StudySession session = new StudySession();
        session.memberId = memberId;
        session.startedAt = LocalDateTime.now();
        session.status = SessionStatus.IN_PROGRESS;
        return session;
    }

    // 세션 종료 처리, 공부 시간(분) 반환
    public long end() {
        this.endedAt = LocalDateTime.now();
        this.status = SessionStatus.COMPLETED;
        return Duration.between(startedAt, endedAt).toMinutes();
    }
}
