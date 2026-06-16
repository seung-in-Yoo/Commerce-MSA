package com.commerce.product.messaging.reply;

import java.util.List;

// product가 stock-replies로 보내는 재고 차감 결과
// order(오케스트레이터)가 구독해 주문을 확정하거나 보상을 진행
public record StockProcessedReply(
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
        return new StockProcessedReply(orderId, Result.DEDUCTED, items, null);
    }

    public static StockProcessedReply failed(Long orderId, String reasonCode) {
        return new StockProcessedReply(orderId, Result.FAILED, List.of(), reasonCode);
    }
}