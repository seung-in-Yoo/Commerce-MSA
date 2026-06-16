package com.commerce.product.messaging.command;

import java.util.List;

// order(오케스트레이터)가 stock-commands로 보내는 재고 차감 명령의 product측 사본
public record DeductStockCommand(
        Long orderId,
        List<Item> items
) {
    public record Item(
            Long productId,
            int quantity
    ) {
    }
}