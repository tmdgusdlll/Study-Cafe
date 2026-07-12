package com.studycafe.domain.point.controller;

import com.studycafe.domain.point.dto.PointBalanceResponse;
import com.studycafe.domain.point.service.PointService;
import com.studycafe.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/points")
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;

    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<PointBalanceResponse>> getBalance(
            @AuthenticationPrincipal Long memberId) {
        return ResponseEntity.ok(ApiResponse.ok(pointService.getBalance(memberId)));
    }
}
