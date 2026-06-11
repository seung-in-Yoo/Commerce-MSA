package com.commerce.order.messaging.event;

import java.util.List;

public record OrderCreatedEvent(
        Long orderId,
        Long customerId,
        List<Item> items
) {
    public record Item(
            Long productId,
            int quantity
    ) {
    }
}