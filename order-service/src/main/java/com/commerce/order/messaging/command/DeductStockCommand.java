package com.commerce.order.messaging.command;

import java.util.List;

// 오케스트레이터 -> product (stock-commands 토픽)
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