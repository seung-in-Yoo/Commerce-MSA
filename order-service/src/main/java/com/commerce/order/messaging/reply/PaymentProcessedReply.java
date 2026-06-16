package com.commerce.order.messaging.reply;

// payment -> 오케스트레이터 (payment-replies 토픽)
// 결제 시도 결과 오케스트레이터는 해당 로직을 받아 다음 단계(재고 차감) 또는 주문 취소를 결정
public record PaymentProcessedReply(
        Long orderId,
        Long paymentId,
        Result result,
        String reasonCode   // FAILED일 때 실패 사유 코드
) {
    public enum Result {
        APPROVED,
        FAILED
    }
}