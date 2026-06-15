package com.commerce.payment.messaging.event;

import java.util.List;

// 결제 처리 결과 이벤트 -> payment-events 토픽으로 발행
// APPROVED면 product가 구독해 재고를 차감하고, FAILED면 order가 구독해 주문을 즉시 취소
public record PaymentProcessedEvent(
        Long orderId,
        Long paymentId,
        Result result,
        long amount,
        List<Item> items,    // APPROVED일 때 다음 단계(재고 차감)로 전달할 주문 항목
        String reasonCode    // FAILED일 때 실패 사유 코드(한도 초과 등)
) {
    public enum Result {
        APPROVED,   // 결제 승인 -> 재고 차감 단계로 진행
        FAILED      // 한도 초과 등으로 승인 거절 -> 주문 취소(보상)
    }

    public record Item(
            Long productId,
            int quantity
    ) {
    }

    public static PaymentProcessedEvent approved(Long orderId, Long paymentId, long amount, List<Item> items) {
        return new PaymentProcessedEvent(orderId, paymentId, Result.APPROVED, amount, items, null);
    }

    public static PaymentProcessedEvent failed(Long orderId, Long paymentId, long amount, String reasonCode) {
        return new PaymentProcessedEvent(orderId, paymentId, Result.FAILED, amount, List.of(), reasonCode);
    }
}