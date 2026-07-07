package com.studycafe.domain.session.service;

import com.studycafe.domain.session.dto.SessionEndResponse;
import com.studycafe.domain.session.dto.SessionStartResponse;
import com.studycafe.domain.session.exception.SessionException;
import com.studycafe.domain.session.repository.StudySessionRepository;
import com.studycafe.global.exception.ErrorCode;
import com.studycafe.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudySessionServiceTest extends IntegrationTestSupport {

    @Autowired
    private StudySessionService studySessionService;

    @Autowired
    private StudySessionRepository studySessionRepository;

    @AfterEach
    void tearDown() {
        studySessionRepository.deleteAll();
    }

    @Test
    void 세션_시작_성공() {
        SessionStartResponse response = studySessionService.start(1L);

        assertThat(response.sessionId()).isNotNull();
        assertThat(response.startedAt()).isNotNull();
    }

    @Test
    void 이미_진행중인_세션이_있으면_예외() {
        studySessionService.start(2L);

        assertThatThrownBy(() -> studySessionService.start(2L))
                .isInstanceOf(SessionException.class)
                .hasMessage(ErrorCode.SESSION_ALREADY_IN_PROGRESS.getMessage());
    }

    @Test
    void 세션_종료_성공_분_기록() {
        studySessionService.start(3L);

        SessionEndResponse response = studySessionService.end(3L);

        assertThat(response.sessionId()).isNotNull();
        assertThat(response.endedAt()).isNotNull();
        assertThat(response.durationMinutes()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 진행중인_세션이_없으면_종료_실패() {
        assertThatThrownBy(() -> studySessionService.end(99L))
                .isInstanceOf(SessionException.class)
                .hasMessage(ErrorCode.SESSION_NOT_FOUND.getMessage());
    }
}
