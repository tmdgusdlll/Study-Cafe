package com.studycafe.domain.member.controller;

import com.studycafe.domain.member.dto.*;
import com.studycafe.domain.member.service.MemberService;
import com.studycafe.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<Void>> signUp(@RequestBody SignUpRequest request) {
        memberService.signUp(request);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(memberService.login(request)));
    }

    // SecurityConfig에서 permitAll() — 액세스 토큰 없어도 호출 가능
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenRefreshResponse>> refresh(
            @RequestBody TokenRefreshRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(memberService.refresh(request)));
    }

    // 인증 필요 — @AuthenticationPrincipal로 memberId 추출
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@AuthenticationPrincipal Long memberId) {
        memberService.logout(memberId);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
