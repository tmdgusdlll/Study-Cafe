package com.studycafe.domain.point.service;

import com.studycafe.domain.point.dto.PointBalanceResponse;
import com.studycafe.domain.point.dto.PointHistoryResponse;
import com.studycafe.domain.point.entity.PointTransactionType;
import com.studycafe.domain.point.repository.PointHistoryRepository;
import com.studycafe.domain.point.repository.UserPointRepository;
import com.studycafe.global.exception.CustomException;
import com.studycafe.global.exception.ErrorCode;
import com.studycafe.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PointServiceTest extends IntegrationTestSupport {

    @Autowired
    private PointService pointService;

    @Autowired
    private UserPointRepository userPointRepository;

    @Autowired
    private PointHistoryRepository pointHistoryRepository;

    @AfterEach
    void tearDown() {
        pointHistoryRepository.deleteAll();
        userPointRepository.deleteAll();
    }

    @Test
    void 포인트_적립_성공() {
        pointService.earn(10L, 100L, PointTransactionType.SESSION_EARN);

        PointBalanceResponse balance = pointService.getBalance(10L);
        assertThat(balance.balance()).isEqualTo(100L);
    }

    @Test
    void 포인트가_없는_회원_잔액_조회_시_0반환() {
        PointBalanceResponse balance = pointService.getBalance(999L);
        assertThat(balance.balance()).isEqualTo(0L);
    }

    @Test
    void 포인트_누적_적립() {
        pointService.earn(20L, 50L, PointTransactionType.SESSION_EARN);
        pointService.earn(20L, 30L, PointTransactionType.SESSION_EARN);

        PointBalanceResponse balance = pointService.getBalance(20L);
        assertThat(balance.balance()).isEqualTo(80L);
    }

    @Test
    void 포인트_차감_성공() {
        pointService.earn(30L, 100L, PointTransactionType.SESSION_EARN);

        pointService.spend(30L, 40L, PointTransactionType.ITEM_PURCHASE);

        assertThat(pointService.getBalance(30L).balance()).isEqualTo(60L);
    }

    @Test
    void 잔액_부족_시_차감_실패() {
        pointService.earn(31L, 10L, PointTransactionType.SESSION_EARN);

        assertThatThrownBy(() -> pointService.spend(31L, 50L, PointTransactionType.ITEM_PURCHASE))
                .isInstanceOf(CustomException.class)
                .hasMessage(ErrorCode.INSUFFICIENT_POINTS.getMessage());
    }

    @Test
    void 포인트_없는_회원_차감_실패() {
        assertThatThrownBy(() -> pointService.spend(999L, 10L, PointTransactionType.ITEM_PURCHASE))
                .isInstanceOf(CustomException.class)
                .hasMessage(ErrorCode.INSUFFICIENT_POINTS.getMessage());
    }

    @Test
    void 포인트_이력_최신순_조회() {
        pointService.earn(40L, 100L, PointTransactionType.SESSION_EARN);
        pointService.spend(40L, 30L, PointTransactionType.ITEM_PURCHASE);

        List<PointHistoryResponse> history = pointService.getHistory(40L);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).amount()).isEqualTo(-30L); // 최신(차감)이 먼저
        assertThat(history.get(1).amount()).isEqualTo(100L);
    }
}
