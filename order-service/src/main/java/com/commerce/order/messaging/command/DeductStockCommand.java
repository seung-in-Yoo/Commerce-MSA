package com.commerce.order.messaging.command;

import java.util.List;
import java.util.UUID;

// 오케스트레이터 -> product (stock-commands 토픽)
// messageId -> 명령 1건의 고유 식별자 (컨슈머가 inbox로 중복 배달을 걸러냄)
public record DeductStockCommand(
        String messageId,
        Long orderId,
        List<Item> items
) {
    public record Item(
            Long productId,
            int quantity
    ) {
    }

    // 발행 시점에 messageId를 새로 찍어 명령을 만듬
    public static DeductStockCommand create(Long orderId, List<Item> items) {
        return new DeductStockCommand(UUID.randomUUID().toString(), orderId, items);
    }
}