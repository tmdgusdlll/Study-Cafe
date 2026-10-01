package com.studycafe.domain.member.controller;

import com.studycafe.domain.member.dto.*;
import com.studycafe.domain.member.repository.MemberRepository;
import com.studycafe.domain.member.repository.RefreshTokenRepository;
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
class MemberControllerTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @AfterEach
    void tearDown() {
        refreshTokenRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 회원가입_성공() throws Exception {
        SignUpRequest request = new SignUpRequest("test@test.com", "password123!", "테스터");

        mockMvc.perform(post("/api/v1/members/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void 이미_존재하는_이메일로_회원가입_실패() throws Exception {
        SignUpRequest first = new SignUpRequest("dup@test.com", "password123!", "첫번째");
        memberService_signUp(first);

        SignUpRequest second = new SignUpRequest("dup@test.com", "password456!", "두번째");

        mockMvc.perform(post("/api/v1/members/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void 로그인_성공_액세스_리프레시_토큰_반환() throws Exception {
        memberService_signUp(new SignUpRequest("login@test.com", "password123!", "로그인테스터"));

        mockMvc.perform(post("/api/v1/members/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("login@test.com", "password123!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());
    }

    @Test
    void 잘못된_비밀번호로_로그인_실패() throws Exception {
        memberService_signUp(new SignUpRequest("wrong@test.com", "password123!", "테스터"));

        mockMvc.perform(post("/api/v1/members/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("wrong@test.com", "wrongpassword!"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
    }

    @Test
    void 리프레시_토큰으로_액세스_토큰_재발급() throws Exception {
        memberService_signUp(new SignUpRequest("refresh@test.com", "password123!", "리프레시테스터"));

        MvcResult loginResult = mockMvc.perform(post("/api/v1/members/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("refresh@test.com", "password123!"))))
                .andReturn();

        String refreshToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("refreshToken").asText();

        mockMvc.perform(post("/api/v1/members/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TokenRefreshRequest(refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());
    }

    @Test
    void 유효하지_않은_리프레시_토큰_재발급_실패() throws Exception {
        mockMvc.perform(post("/api/v1/members/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TokenRefreshRequest("invalid-token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void 액세스_토큰으로_내_정보_조회() throws Exception {
        memberService_signUp(new SignUpRequest("me@test.com", "password123!", "내정보테스터"));

        MvcResult loginResult = mockMvc.perform(post("/api/v1/members/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("me@test.com", "password123!"))))
                .andReturn();

        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();

        mockMvc.perform(get("/api/v1/members/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("me@test.com"))
                .andExpect(jsonPath("$.data.nickname").value("내정보테스터"));
    }

    @Test
    void 토큰_없이_내_정보_조회_실패() throws Exception {
        mockMvc.perform(get("/api/v1/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 잘못된_이메일_형식으로_회원가입_실패() throws Exception {
        SignUpRequest request = new SignUpRequest("not-an-email", "password123!", "테스터");

        mockMvc.perform(post("/api/v1/members/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("이메일 형식이 올바르지 않습니다"));
    }

    @Test
    void 닉네임이_너무_짧으면_회원가입_실패() throws Exception {
        SignUpRequest request = new SignUpRequest("short@test.com", "password123!", "a");

        mockMvc.perform(post("/api/v1/members/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("닉네임은 2~10자여야 합니다"));
    }

    private void memberService_signUp(SignUpRequest request) throws Exception {
        mockMvc.perform(post("/api/v1/members/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }
}
