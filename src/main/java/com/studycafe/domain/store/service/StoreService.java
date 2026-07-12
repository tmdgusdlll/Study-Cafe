package com.studycafe.domain.store.service;

import com.studycafe.domain.point.entity.PointTransactionType;
import com.studycafe.domain.point.service.PointService;
import com.studycafe.domain.store.dto.StoreItemResponse;
import com.studycafe.domain.store.entity.StoreItem;
import com.studycafe.domain.store.entity.UserItem;
import com.studycafe.domain.store.repository.StoreItemRepository;
import com.studycafe.domain.store.repository.UserItemRepository;
import com.studycafe.global.exception.CustomException;
import com.studycafe.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreItemRepository storeItemRepository;
    private final UserItemRepository userItemRepository;
    private final PointService pointService;

    @Transactional(readOnly = true)
    public List<StoreItemResponse> getItems() {
        return storeItemRepository.findAll().stream()
                .map(StoreItemResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StoreItemResponse> getMyItems(Long memberId) {
        // 1) 내가 보유한 storeItemId 목록
        List<Long> storeItemIds = userItemRepository.findByMemberId(memberId).stream()
                .map(UserItem::getStoreItemId)
                .toList();
        // 2) findAllById 배치 조회 — 쿼리 1번으로 N+1 방지
        return storeItemRepository.findAllById(storeItemIds).stream()
                .map(StoreItemResponse::from)
                .toList();
    }

    @Transactional
    public void purchase(Long memberId, Long itemId) {
        StoreItem item = storeItemRepository.findById(itemId)
                .orElseThrow(() -> new CustomException(ErrorCode.ITEM_NOT_FOUND));

        if (userItemRepository.existsByMemberIdAndStoreItemId(memberId, itemId)) {
            throw new CustomException(ErrorCode.ITEM_ALREADY_OWNED);
        }

        pointService.spend(memberId, item.getPrice(), PointTransactionType.ITEM_PURCHASE);
        userItemRepository.save(UserItem.of(memberId, itemId));
    }
}
