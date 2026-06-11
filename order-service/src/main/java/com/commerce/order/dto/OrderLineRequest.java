package com.commerce.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// 주문 항목 요청 클라이언트는 productId와 quantity만 보냄 -> 이름/가격은 product-service가 소유하는 데이터라 클라가 정하지 않음
public record OrderLineRequest(
        @NotNull Long productId,
        @Positive int quantity
) {
}