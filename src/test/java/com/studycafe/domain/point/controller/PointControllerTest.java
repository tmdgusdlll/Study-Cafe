package com.studycafe.domain.point.controller;

import com.studycafe.domain.member.dto.LoginRequest;
import com.studycafe.domain.member.dto.SignUpRequest;
import com.studycafe.domain.member.repository.MemberRepository;
import com.studycafe.domain.member.repository.RefreshTokenRepository;
import com.studycafe.domain.point.repository.PointHistoryRepository;
import com.studycafe.domain.point.repository.UserPointRepository;
import com.studycafe.domain.session.repository.StudySessionRepository;
import com.studycafe.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PointControllerTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private StudySessionRepository studySessionRepository;

    @Autowired
    private UserPointRepository userPointRepository;

    @Autowired
    private PointHistoryRepository pointHistoryRepository;

    @AfterEach
    void tearDown() {
        pointHistoryRepository.deleteAll();
        userPointRepository.deleteAll();
        studySessionRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void MVP_전체_흐름_세션종료_후_포인트_적립_잔액조회() throws Exception {
        // 1. 회원가입
        mockMvc.perform(post("/api/v1/members/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new SignUpRequest("mvp@test.com", "password123!", "MVP테스터"))));

        // 2. 로그인 → JWT 토큰 획득
        MvcResult loginResult = mockMvc.perform(post("/api/v1/members/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("mvp@test.com", "password123!"))))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();

        // 3. 세션 시작
        mockMvc.perform(post("/api/v1/sessions/start")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 4. 세션 종료 (0분 이하라서 earnedPoints=0이지만 구조는 검증)
        mockMvc.perform(post("/api/v1/sessions/end")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.durationMinutes").isNumber())
                .andExpect(jsonPath("$.data.earnedPoints").isNumber());

        // 5. 포인트 잔액 조회
        mockMvc.perform(get("/api/v1/points/balance")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.balance").isNumber());
    }

    @Test
    void 인증_없이_잔액조회_401() throws Exception {
        mockMvc.perform(get("/api/v1/points/balance"))
                .andExpect(status().isUnauthorized());
    }
}
