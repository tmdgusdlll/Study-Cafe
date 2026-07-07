package com.studycafe.domain.member.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, unique = true)
    private Long memberId;

    @Column(nullable = false)
    private String token;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    public static RefreshToken issue(Long memberId, long expirationMs) {
        RefreshToken rt = new RefreshToken();
        rt.memberId = memberId;
        rt.token = UUID.randomUUID().toString();
        rt.expiresAt = LocalDateTime.now().plusSeconds(expirationMs / 1000);
        return rt;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    // 로테이션: 새 UUID로 교체
    public void rotate(long expirationMs) {
        this.token = UUID.randomUUID().toString();
        this.expiresAt = LocalDateTime.now().plusSeconds(expirationMs / 1000);
    }
}
