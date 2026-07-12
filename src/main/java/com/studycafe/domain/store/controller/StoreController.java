package com.studycafe.domain.store.controller;

import com.studycafe.domain.store.dto.StoreItemResponse;
import com.studycafe.domain.store.service.StoreService;
import com.studycafe.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/store")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    @GetMapping("/items")
    public ResponseEntity<ApiResponse<List<StoreItemResponse>>> getItems() {
        return ResponseEntity.ok(ApiResponse.ok(storeService.getItems()));
    }

    @GetMapping("/my-items")
    public ResponseEntity<ApiResponse<List<StoreItemResponse>>> getMyItems(
            @AuthenticationPrincipal Long memberId) {
        return ResponseEntity.ok(ApiResponse.ok(storeService.getMyItems(memberId)));
    }

    @PostMapping("/items/{itemId}/purchase")
    public ResponseEntity<ApiResponse<Void>> purchase(
            @AuthenticationPrincipal Long memberId, @PathVariable Long itemId) {
        storeService.purchase(memberId, itemId);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
