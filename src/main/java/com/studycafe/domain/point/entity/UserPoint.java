package com.studycafe.domain.point.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_points")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, unique = true)
    private Long memberId;

    @Column(nullable = false)
    private Long balance = 0L;

    @Version
    private Long version;

    public static UserPoint of(Long memberId) {
        UserPoint userPoint = new UserPoint();
        userPoint.memberId = memberId;
        userPoint.balance = 0L;
        return userPoint;
    }

    public void earn(long amount) {
        this.balance += amount;
    }
}
