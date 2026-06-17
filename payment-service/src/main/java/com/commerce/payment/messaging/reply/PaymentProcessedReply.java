package com.commerce.payment.messaging.reply;

import java.util.UUID;

// payment가 payment-replies로 보내는 결제 결과
// order(오케스트레이터)가 구독해 다음 단계(재고 차감) 또는 주문 취소를 결정
// messageId: 해당 응답 1건의 고유 식별자 (컨슈머(order)가 inbox로 중복 배달을 걸러냄)
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

    public static PaymentProcessedReply approved(Long orderId, Long paymentId) {
        return new PaymentProcessedReply(UUID.randomUUID().toString(), orderId, paymentId, Result.APPROVED, null);
    }

    public static PaymentProcessedReply failed(Long orderId, Long paymentId, String reasonCode) {
        return new PaymentProcessedReply(UUID.randomUUID().toString(), orderId, paymentId, Result.FAILED, reasonCode);
    }
}