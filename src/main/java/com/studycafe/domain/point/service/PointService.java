package com.studycafe.domain.point.service;

import com.studycafe.domain.point.dto.PointBalanceResponse;
import com.studycafe.domain.point.dto.PointHistoryResponse;
import com.studycafe.domain.point.entity.PointHistory;
import com.studycafe.domain.point.entity.PointTransactionType;
import com.studycafe.domain.point.entity.UserPoint;
import com.studycafe.domain.point.repository.PointHistoryRepository;
import com.studycafe.domain.point.repository.UserPointRepository;
import com.studycafe.global.exception.CustomException;
import com.studycafe.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    public void spend(Long memberId, long amount, PointTransactionType type) {
        // 잔액이 없는(UserPoint 미존재) 회원은 곧 잔액 0이므로 부족 예외로 처리한다.
        UserPoint userPoint = userPointRepository.findByMemberId(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.INSUFFICIENT_POINTS));
        userPoint.spend(amount);
        pointHistoryRepository.save(PointHistory.of(memberId, -amount, type));
    }

    @Transactional(readOnly = true)
    public PointBalanceResponse getBalance(Long memberId) {
        long balance = userPointRepository.findByMemberId(memberId)
                .map(UserPoint::getBalance)
                .orElse(0L);
        return new PointBalanceResponse(memberId, balance);
    }

    @Transactional(readOnly = true)
    public List<PointHistoryResponse> getHistory(Long memberId) {
        return pointHistoryRepository.findByMemberIdOrderByIdDesc(memberId).stream()
                .map(PointHistoryResponse::from)
                .toList();
    }
}
