package com.commerce.order.messaging.event;

import java.util.List;

// product-service가 product-events로 발행하는 재고 처리 결과 이벤트 order측 사본
// 서비스 간 클래스는 공유하지 않으므로 order가 자기 패키지에 동일 구조로 정의한다
public record StockProcessedEvent(
        Long orderId,
        Result result,
        List<Item> items,    // DEDUCTED일 때 차감된 상품의 이름/단가 스냅샷. FAILED면 빈 리스트
        String reasonCode    // FAILED일 때 실패 사유 코드. DEDUCTED면 null
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
}