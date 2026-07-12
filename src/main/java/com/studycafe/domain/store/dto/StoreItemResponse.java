package com.studycafe.domain.store.dto;

import com.studycafe.domain.store.entity.ItemCategory;
import com.studycafe.domain.store.entity.StoreItem;

public record StoreItemResponse(Long id, String name, String description,
                                ItemCategory category, Long price, String resourcePath) {

    public static StoreItemResponse from(StoreItem item) {
        return new StoreItemResponse(item.getId(), item.getName(), item.getDescription(),
                item.getCategory(), item.getPrice(), item.getResourcePath());
    }
}
