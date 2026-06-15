package com.commerce.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// 주문 항목 요청
// 예상 단가를 받아 결제용 금액을 만듬 (진짜 단가는 재고 차감 후 product가 확정)
public record OrderLineRequest(
        @NotNull Long productId,
        @Positive int quantity,
        @Positive long unitPrice // 예상 단가
) {
}