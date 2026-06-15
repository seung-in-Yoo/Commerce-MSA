package com.commerce.order.domain;

// 주문 상태
public enum OrderStatus {
    PENDING,    // 주문 생성됨, 재고 차감 결과를 기다리는 중
    CONFIRMED,  // 재고 차감 성공 → 주문 확정
    CANCELLED   // 재고 차감 실패 등으로 취소
}