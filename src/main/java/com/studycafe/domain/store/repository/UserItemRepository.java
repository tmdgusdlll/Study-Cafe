package com.studycafe.domain.store.repository;

import com.studycafe.domain.store.entity.UserItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserItemRepository extends JpaRepository<UserItem, Long> {

    boolean existsByMemberIdAndStoreItemId(Long memberId, Long storeItemId);

    List<UserItem> findByMemberId(Long memberId);
}
