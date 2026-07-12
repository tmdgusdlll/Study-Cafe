package com.studycafe.domain.point.dto;

import com.studycafe.domain.point.entity.PointHistory;
import com.studycafe.domain.point.entity.PointTransactionType;

import java.time.LocalDateTime;

public record PointHistoryResponse(long amount, PointTransactionType type, LocalDateTime createdAt) {

    public static PointHistoryResponse from(PointHistory history) {
        return new PointHistoryResponse(history.getAmount(), history.getType(), history.getCreatedAt());
    }
}
