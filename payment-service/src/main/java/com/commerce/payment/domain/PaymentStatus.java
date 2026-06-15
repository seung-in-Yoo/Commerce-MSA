package com.commerce.payment.domain;

// 결제 상태
public enum PaymentStatus {
    APPROVED,   // 결제 승인됨
    FAILED,     // 한도 초과 등으로 승인 거절
    REFUNDED    // 승인됐으나 이후 재고 실패로 환불됨(보상)
}