package com.studycafe.domain.member.dto;

public record TokenRefreshResponse(String accessToken, String refreshToken) {
}
