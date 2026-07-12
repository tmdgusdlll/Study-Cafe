package com.studycafe.domain.point.repository;

import com.studycafe.domain.point.entity.PointHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {

    List<PointHistory> findByMemberIdOrderByIdDesc(Long memberId);
}
