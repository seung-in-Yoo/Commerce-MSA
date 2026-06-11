package com.commerce.product.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

// 재고 차감 결과 -> order-service는 해당 값을 주문 항목 스냅샷으로 저장
@Getter
@Builder
public class StockDeductResponse {

    private final List<Item> items;

    @Getter
    @Builder
    public static class Item {
        private final Long productId;
        private final String productName;
        private final long unitPrice;
        private final int quantity;
    }
}