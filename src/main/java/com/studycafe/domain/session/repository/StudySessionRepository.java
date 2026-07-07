package com.studycafe.domain.session.repository;

import com.studycafe.domain.session.entity.SessionStatus;
import com.studycafe.domain.session.entity.StudySession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {

    Optional<StudySession> findByMemberIdAndStatus(Long memberId, SessionStatus status);
}
