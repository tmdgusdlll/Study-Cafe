package com.studycafe.domain.point.service;

import com.studycafe.domain.point.dto.PointBalanceResponse;
import com.studycafe.domain.point.entity.PointHistory;
import com.studycafe.domain.point.entity.PointTransactionType;
import com.studycafe.domain.point.entity.UserPoint;
import com.studycafe.domain.point.repository.PointHistoryRepository;
import com.studycafe.domain.point.repository.UserPointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class PointService {

    private final UserPointRepository userPointRepository;
    private final PointHistoryRepository pointHistoryRepository;

    public void earn(Long memberId, long amount, PointTransactionType type) {
        UserPoint userPoint = userPointRepository.findByMemberId(memberId)
                .orElseGet(() -> userPointRepository.save(UserPoint.of(memberId)));
        userPoint.earn(amount);
        pointHistoryRepository.save(PointHistory.of(memberId, amount, type));
    }

    @Transactional(readOnly = true)
    public PointBalanceResponse getBalance(Long memberId) {
        long balance = userPointRepository.findByMemberId(memberId)
                .map(UserPoint::getBalance)
                .orElse(0L);
        return new PointBalanceResponse(memberId, balance);
    }
}
