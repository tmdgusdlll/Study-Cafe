package com.studycafe.domain.member.service;

import com.studycafe.domain.member.dto.SignUpRequest;
import com.studycafe.domain.member.entity.Member;
import com.studycafe.domain.member.exception.MemberException;
import com.studycafe.domain.member.repository.MemberRepository;
import com.studycafe.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public void signUp(SignUpRequest request) {
        if (memberRepository.existsByEmail(request.email())) {
            throw new MemberException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        memberRepository.save(Member.of(request.email(), passwordEncoder.encode(request.password()), request.nickname()));
    }
}
