package com.studycafe.domain.point.service;

import com.studycafe.domain.point.dto.PointBalanceResponse;
import com.studycafe.domain.point.entity.PointTransactionType;
import com.studycafe.domain.point.repository.PointHistoryRepository;
import com.studycafe.domain.point.repository.UserPointRepository;
import com.studycafe.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

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
}
