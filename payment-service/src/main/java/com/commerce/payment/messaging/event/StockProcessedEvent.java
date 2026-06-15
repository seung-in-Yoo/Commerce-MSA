package com.commerce.payment.messaging.event;

// product-service가 product-events로 발행하는 재고 처리 결과 이벤트의 payment측 사본
// payment는 재고 실패일 때 해당 주문의 결제를 환불(보상)하기 위해 구독
public record StockProcessedEvent(
        Long orderId,
        Result result,
        String reasonCode    // FAILED일 때 실패 사유 코드
) {
    public enum Result {
        DEDUCTED,
        FAILED
    }
}