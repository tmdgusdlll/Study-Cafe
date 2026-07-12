package com.studycafe.domain.point.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "point_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PointTransactionType type;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public static PointHistory of(Long memberId, long amount, PointTransactionType type) {
        PointHistory history = new PointHistory();
        history.memberId = memberId;
        history.amount = amount;
        history.type = type;
        history.createdAt = LocalDateTime.now();
        return history;
    }
}
