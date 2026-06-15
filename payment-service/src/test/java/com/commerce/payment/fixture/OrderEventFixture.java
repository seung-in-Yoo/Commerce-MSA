package com.commerce.payment.fixture;

import com.commerce.payment.messaging.event.OrderCreatedEvent;

import java.util.List;

public class OrderEventFixture {

    // amount=660000 -> 결제 승인 케이스
    public static OrderCreatedEvent defaultEvent() {
        return new OrderCreatedEvent(1L, 1L, 660000L, List.of(
                new OrderCreatedEvent.Item(1L, 2),
                new OrderCreatedEvent.Item(3L, 1)));
    }

    public static OrderCreatedEvent event(Long orderId, long amount, List<OrderCreatedEvent.Item> items) {
        return new OrderCreatedEvent(orderId, 1L, amount, items);
    }
}