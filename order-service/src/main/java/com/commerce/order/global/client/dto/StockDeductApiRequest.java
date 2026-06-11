package com.commerce.order.global.client.dto;

import java.util.List;

// product-service의 재고 차감 엔드포인트로 보낼 요청 바디
public record StockDeductApiRequest(
        List<Line> items
) {
    public record Line(
            Long productId,
            int quantity
    ) {
    }
}