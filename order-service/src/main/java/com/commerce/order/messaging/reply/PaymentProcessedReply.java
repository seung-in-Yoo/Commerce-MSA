package com.commerce.order.messaging.reply;

// payment -> 오케스트레이터 (payment-replies 토픽)
// 결제 시도 결과 오케스트레이터는 해당 로직을 받아 다음 단계(재고 차감) 또는 주문 취소를 결정
// messageId -> 이 응답 1건의 고유 식별자 (inbox에 기록해 중복 배달을 거름)
public record PaymentProcessedReply(
        String messageId,
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