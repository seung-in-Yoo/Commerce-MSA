package com.commerce.payment.messaging.event;

import java.util.List;

// order-service가 order-events로 발행하는 주문 생성 이벤트의 payment측 사본
public record OrderCreatedEvent(
        Long orderId,
        Long customerId,
        long amount,
        List<Item> items
) {
    public record Item(
            Long productId,
            int quantity
    ) {
    }
}