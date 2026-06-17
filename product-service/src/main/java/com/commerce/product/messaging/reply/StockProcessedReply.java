package com.commerce.product.messaging.reply;

import java.util.List;
import java.util.UUID;

// product가 stock-replies로 보내는 재고 차감 결과
// order(오케스트레이터)가 구독해 주문을 확정하거나 보상을 진행
// messageId: 해당 응답 1건의 고유 식별자 (컨슈머(order)가 inbox로 중복 배달을 걸러냄)
public record StockProcessedReply(
        String messageId,
        Long orderId,
        Result result,
        List<Item> items,   // DEDUCTED일 때 상품 이름/단가 스냅샷
        String reasonCode   // FAILED일 때 실패 사유 코드
) {
    public enum Result {
        DEDUCTED,
        FAILED
    }

    public record Item(
            Long productId,
            String productName,
            long unitPrice
    ) {
    }

    public static StockProcessedReply deducted(Long orderId, List<Item> items) {
        return new StockProcessedReply(UUID.randomUUID().toString(), orderId, Result.DEDUCTED, items, null);
    }

    public static StockProcessedReply failed(Long orderId, String reasonCode) {
        return new StockProcessedReply(UUID.randomUUID().toString(), orderId, Result.FAILED, List.of(), reasonCode);
    }
}