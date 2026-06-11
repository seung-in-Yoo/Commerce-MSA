package com.commerce.order.global.client.dto;

import java.util.List;

// product-service 재고 차감 응답의 data
public record StockDeductApiResponse(
        List<Item> items
) {
    public record Item(
            Long productId,
            String productName,
            long unitPrice,
            int quantity
    ) {
    }
}