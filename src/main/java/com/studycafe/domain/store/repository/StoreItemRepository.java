package com.studycafe.domain.store.repository;

import com.studycafe.domain.store.entity.StoreItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreItemRepository extends JpaRepository<StoreItem, Long> {
}
