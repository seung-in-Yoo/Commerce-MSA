package com.commerce.product.messaging.event;

import java.util.List;

// 재고 차감 처리 결과를 order-service로 돌려보내는 역방향 이벤트
// product-events 토픽으로 발행되고, order가 구독해 주문을 CONFIRMED/CANCELLED로 전이시킴
public record StockProcessedEvent(
        Long orderId,
        Result result,
        List<Item> items,    // DEDUCTED일 때만 채워짐(차감된 상품의 이름/단가 스냅샷) -> FAILED면 빈 리스트
        String reasonCode    // FAILED일 때 실패 사유 코드(DEDUCTED면 null)
) {
    public enum Result {
        DEDUCTED,   // 재고 차감 성공
        FAILED      // 재고 부족/상품 없음 등으로 차감 실패
    }

    public record Item(
            Long productId,
            String productName,
            long unitPrice
    ) {
    }

    public static StockProcessedEvent deducted(Long orderId, List<Item> items) {
        return new StockProcessedEvent(orderId, Result.DEDUCTED, items, null);
    }

    public static StockProcessedEvent failed(Long orderId, String reasonCode) {
        return new StockProcessedEvent(orderId, Result.FAILED, List.of(), reasonCode);
    }
}