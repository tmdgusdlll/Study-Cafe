package com.studycafe.domain.point.entity;

public enum PointTransactionType {
    SESSION_EARN,     // 세션 완료 자동 적립
    PAYMENT_CHARGE,   // 포트원 결제 충전 (Plan 1)
    ITEM_PURCHASE     // 아이템 구매 (Plan 1)
}
