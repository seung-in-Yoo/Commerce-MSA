package com.commerce.product.messaging.command;

import java.util.List;

// order(오케스트레이터)가 stock-commands로 보내는 재고 차감 명령의 product측 사본
// messageId: 해당 명령 1건의 고유 식별자 (inbox에 기록해 중복 배달을 거름)
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
}