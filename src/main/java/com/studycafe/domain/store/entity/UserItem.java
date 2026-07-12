package com.studycafe.domain.store.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_items",
        uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "store_item_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "store_item_id", nullable = false)
    private Long storeItemId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime purchasedAt;

    public static UserItem of(Long memberId, Long storeItemId) {
        UserItem userItem = new UserItem();
        userItem.memberId = memberId;
        userItem.storeItemId = storeItemId;
        userItem.purchasedAt = LocalDateTime.now();
        return userItem;
    }
}
