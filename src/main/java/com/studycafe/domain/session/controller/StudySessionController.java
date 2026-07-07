package com.studycafe.domain.session.controller;

import com.studycafe.domain.session.dto.SessionEndResponse;
import com.studycafe.domain.session.dto.SessionStartResponse;
import com.studycafe.domain.session.service.StudySessionService;
import com.studycafe.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class StudySessionController {

    private final StudySessionService studySessionService;

    @PostMapping("/start")
    public ResponseEntity<ApiResponse<SessionStartResponse>> start(
            @AuthenticationPrincipal Long memberId) {
        return ResponseEntity.ok(ApiResponse.ok(studySessionService.start(memberId)));
    }

    @PostMapping("/end")
    public ResponseEntity<ApiResponse<SessionEndResponse>> end(
            @AuthenticationPrincipal Long memberId) {
        return ResponseEntity.ok(ApiResponse.ok(studySessionService.end(memberId)));
    }
}
