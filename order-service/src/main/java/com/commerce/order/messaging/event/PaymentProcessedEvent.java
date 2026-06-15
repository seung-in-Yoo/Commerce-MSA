package com.commerce.order.messaging.event;

// payment-service가 payment-events로 발행하는 결제 결과 이벤트의 order측 사본
// order는 결제 거절(FAILED)일 때 주문을 즉시 취소(보상)하기 위해 구독
public record PaymentProcessedEvent(
        Long orderId,
        Long paymentId,
        Result result,
        long amount,
        String reasonCode    // FAILED일 때 실패 사유 코드
) {
    public enum Result {
        APPROVED,
        FAILED
    }
}