package com.studycafe.domain.store.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItemCategory category;

    @Column(nullable = false)
    private Long price;

    @Column(nullable = false)
    private String resourcePath;

    public static StoreItem of(String name, String description, ItemCategory category,
                               Long price, String resourcePath) {
        StoreItem item = new StoreItem();
        item.name = name;
        item.description = description;
        item.category = category;
        item.price = price;
        item.resourcePath = resourcePath;
        return item;
    }
}
