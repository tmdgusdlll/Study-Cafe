package com.studycafe.domain.member.service;

import com.studycafe.domain.member.dto.*;
import com.studycafe.domain.member.entity.Member;
import com.studycafe.domain.member.entity.RefreshToken;
import com.studycafe.domain.member.exception.MemberException;
import com.studycafe.domain.member.repository.MemberRepository;
import com.studycafe.domain.member.repository.RefreshTokenRepository;
import com.studycafe.global.exception.ErrorCode;
import com.studycafe.infra.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class MemberService {

    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    @Value("${jwt.refresh-expiration-ms}")
    private long refreshExpirationMs;

    public void signUp(SignUpRequest request) {
        if (memberRepository.existsByEmail(request.email())) {
            throw new MemberException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        memberRepository.save(Member.of(request.email(), passwordEncoder.encode(request.password()), request.nickname()));
    }

    public LoginResponse login(LoginRequest request) {
        Member member = memberRepository.findByEmail(request.email())
                .orElseThrow(() -> new MemberException(ErrorCode.MEMBER_NOT_FOUND));
        if (!passwordEncoder.matches(request.password(), member.getPassword())) {
            throw new MemberException(ErrorCode.INVALID_PASSWORD);
        }
        String accessToken = jwtProvider.generate(member.getId());
        // 기존 리프레시 토큰 삭제 후 재발급 (중복 로그인 처리)
        refreshTokenRepository.deleteByMemberId(member.getId());
        RefreshToken refreshToken = refreshTokenRepository.save(
                RefreshToken.issue(member.getId(), refreshExpirationMs));
        return new LoginResponse(accessToken, refreshToken.getToken());
    }

    public TokenRefreshResponse refresh(TokenRefreshRequest request) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(request.refreshToken())
                .orElseThrow(() -> new MemberException(ErrorCode.REFRESH_TOKEN_INVALID));
        if (refreshToken.isExpired()) {
            refreshTokenRepository.delete(refreshToken);
            throw new MemberException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        // 로테이션: 새 UUID로 교체
        refreshToken.rotate(refreshExpirationMs);
        String newAccessToken = jwtProvider.generate(refreshToken.getMemberId());
        return new TokenRefreshResponse(newAccessToken, refreshToken.getToken());
    }

    public void logout(Long memberId) {
        refreshTokenRepository.deleteByMemberId(memberId);
    }
}
