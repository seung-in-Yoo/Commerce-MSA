package com.commerce.payment.domain;

// 결제 상태
public enum PaymentStatus {
    APPROVED,   // 결제 승인됨
    FAILED      // 한도 초과 등으로 승인 거절
}