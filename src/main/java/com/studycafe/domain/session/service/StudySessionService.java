package com.studycafe.domain.session.service;

import com.studycafe.domain.session.dto.SessionEndResponse;
import com.studycafe.domain.session.dto.SessionStartResponse;
import com.studycafe.domain.session.entity.SessionStatus;
import com.studycafe.domain.session.entity.StudySession;
import com.studycafe.domain.session.exception.SessionException;
import com.studycafe.domain.session.repository.StudySessionRepository;
import com.studycafe.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class StudySessionService {

    private final StudySessionRepository studySessionRepository;

    public SessionStartResponse start(Long memberId) {
        studySessionRepository.findByMemberIdAndStatus(memberId, SessionStatus.IN_PROGRESS)
                .ifPresent(s -> { throw new SessionException(ErrorCode.SESSION_ALREADY_IN_PROGRESS); });

        StudySession session = studySessionRepository.save(StudySession.start(memberId));
        return SessionStartResponse.from(session);
    }

    public SessionEndResponse end(Long memberId) {
        StudySession session = studySessionRepository.findByMemberIdAndStatus(memberId, SessionStatus.IN_PROGRESS)
                .orElseThrow(() -> new SessionException(ErrorCode.SESSION_NOT_FOUND));

        long durationMinutes = session.end();
        studySessionRepository.save(session);

        // 포인트 적립은 Task 6에서 PointService 주입 후 추가
        return SessionEndResponse.of(session, durationMinutes, 0L);
    }
}
