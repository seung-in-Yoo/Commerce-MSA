package com.commerce.order.messaging.command;

import java.util.UUID;

// 오케스트레이터 -> payment (payment-refund-commands 토픽)
// 보상: 재고 차감이 실패했을 때 이미 한 결제를 환불
// messageId: 이 명령 1건의 고유 식별자 (컨슈머(payment)가 inbox로 중복 배달을 걸러냄)
public record RefundPaymentCommand(
        String messageId,
        Long orderId
) {
    // 발행 시점에 messageId를 새로 찍어 명령을 만듬
    public static RefundPaymentCommand create(Long orderId) {
        return new RefundPaymentCommand(UUID.randomUUID().toString(), orderId);
    }
}