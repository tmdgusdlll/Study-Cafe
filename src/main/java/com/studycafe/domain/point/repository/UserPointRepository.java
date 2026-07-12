package com.studycafe.domain.point.repository;

import com.studycafe.domain.point.entity.UserPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserPointRepository extends JpaRepository<UserPoint, Long> {

    Optional<UserPoint> findByMemberId(Long memberId);
}
