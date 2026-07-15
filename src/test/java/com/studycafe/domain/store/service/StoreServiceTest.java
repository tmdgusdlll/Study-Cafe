package com.studycafe.domain.store.service;

import com.studycafe.domain.point.entity.PointTransactionType;
import com.studycafe.domain.point.repository.PointHistoryRepository;
import com.studycafe.domain.point.repository.UserPointRepository;
import com.studycafe.domain.point.service.PointService;
import com.studycafe.domain.store.dto.StoreItemResponse;
import com.studycafe.domain.store.entity.ItemCategory;
import com.studycafe.domain.store.entity.StoreItem;
import com.studycafe.domain.store.repository.StoreItemRepository;
import com.studycafe.domain.store.repository.UserItemRepository;
import com.studycafe.global.exception.CustomException;
import com.studycafe.global.exception.ErrorCode;
import com.studycafe.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreServiceTest extends IntegrationTestSupport {

    @Autowired
    private StoreService storeService;

    @Autowired
    private StoreItemRepository storeItemRepository;

    @Autowired
    private UserItemRepository userItemRepository;

    @Autowired
    private PointService pointService;

    @Autowired
    private UserPointRepository userPointRepository;

    @Autowired
    private PointHistoryRepository pointHistoryRepository;

    @AfterEach
    void tearDown() {
        userItemRepository.deleteAll();
        storeItemRepository.deleteAll();
        pointHistoryRepository.deleteAll();
        userPointRepository.deleteAll();
    }

    private StoreItem saveItem(long price) {
        return storeItemRepository.save(
                StoreItem.of("빈티지 카페", "따뜻한 빈티지 분위기", ItemCategory.BACKGROUND, price, "/bg/vintage.png"));
    }

    @Test
    void 구매_성공_포인트차감_아이템저장() {
        StoreItem item = saveItem(500L);
        pointService.earn(1L, 1000L, PointTransactionType.SESSION_EARN);

        storeService.purchase(1L, item.getId());

        assertThat(pointService.getBalance(1L).balance()).isEqualTo(500L);
        assertThat(userItemRepository.existsByMemberIdAndStoreItemId(1L, item.getId())).isTrue();
    }

    @Test
    void 포인트_부족_시_구매_실패하고_아이템_미저장() {
        StoreItem item = saveItem(500L);
        pointService.earn(2L, 100L, PointTransactionType.SESSION_EARN);

        assertThatThrownBy(() -> storeService.purchase(2L, item.getId()))
                .isInstanceOf(CustomException.class)
                .hasMessage(ErrorCode.INSUFFICIENT_POINTS.getMessage());

        assertThat(userItemRepository.existsByMemberIdAndStoreItemId(2L, item.getId())).isFalse();
    }

    @Test
    void 이미_보유한_아이템_구매_실패() {
        StoreItem item = saveItem(300L);
        pointService.earn(3L, 1000L, PointTransactionType.SESSION_EARN);
        storeService.purchase(3L, item.getId());

        assertThatThrownBy(() -> storeService.purchase(3L, item.getId()))
                .isInstanceOf(CustomException.class)
                .hasMessage(ErrorCode.ITEM_ALREADY_OWNED.getMessage());
    }

    @Test
    void 존재하지_않는_아이템_구매_실패() {
        assertThatThrownBy(() -> storeService.purchase(4L, 999L))
                .isInstanceOf(CustomException.class)
                .hasMessage(ErrorCode.ITEM_NOT_FOUND.getMessage());
    }

    @Test
    void 전체_아이템_목록_조회() {
        StoreItem itemA = saveItem(500L);
        StoreItem itemB = storeItemRepository.save(
                StoreItem.of("모던 카페", "깔끔한 모던 분위기", ItemCategory.BACKGROUND, 700L, "/bg/modern.png"));

        List<StoreItemResponse> items = storeService.getItems();

        assertThat(items).hasSize(2);
        assertThat(items).extracting(StoreItemResponse::id)
                .containsExactlyInAnyOrder(itemA.getId(), itemB.getId());
        assertThat(items).extracting(StoreItemResponse::name)
                .containsExactlyInAnyOrder(itemA.getName(), itemB.getName());
    }

    @Test
    void 내_보유_아이템만_조회() {
        StoreItem itemA = saveItem(500L);
        StoreItem itemB = storeItemRepository.save(
                StoreItem.of("모던 카페", "깔끔한 모던 분위기", ItemCategory.BACKGROUND, 700L, "/bg/modern.png"));
        pointService.earn(10L, 1000L, PointTransactionType.SESSION_EARN);
        storeService.purchase(10L, itemA.getId());

        List<StoreItemResponse> myItems = storeService.getMyItems(10L);

        assertThat(myItems).hasSize(1);
        assertThat(myItems.get(0).id()).isEqualTo(itemA.getId());
        assertThat(myItems.get(0).name()).isEqualTo(itemA.getName());
    }

    @Test
    void 보유_아이템_없으면_빈_목록() {
        assertThat(storeService.getMyItems(999L)).isEmpty();
    }
}
