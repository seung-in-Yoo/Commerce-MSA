package com.commerce.product.fixture;

import com.commerce.product.messaging.event.OrderCreatedEvent;

import java.util.List;

public class OrderEventFixture {

    public static OrderCreatedEvent defaultEvent() {
        return new OrderCreatedEvent(1L, 1L, List.of(
                new OrderCreatedEvent.Item(1L, 2),
                new OrderCreatedEvent.Item(3L, 1)));
    }

    public static OrderCreatedEvent event(Long orderId, List<OrderCreatedEvent.Item> items) {
        return new OrderCreatedEvent(orderId, 1L, items);
    }
}