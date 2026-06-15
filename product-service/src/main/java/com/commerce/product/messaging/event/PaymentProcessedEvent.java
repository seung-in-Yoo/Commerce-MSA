package com.commerce.product.messaging.event;

import java.util.List;

// payment-service가 payment-events로 발행하는 결제 처리 결과 이벤트의 product측 사본
// product는 APPROVED일 때만 items로 재고를 차감
public record PaymentProcessedEvent(
        Long orderId,
        Long paymentId,
        Result result,
        long amount,
        List<Item> items,    // APPROVED일 때 차감할 주문 항목
        String reasonCode    // FAILED일 때 실패 사유 코드
) {
    public enum Result {
        APPROVED,
        FAILED
    }

    public record Item(
            Long productId,
            int quantity
    ) {
    }
}