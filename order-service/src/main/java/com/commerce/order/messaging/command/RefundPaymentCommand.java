package com.commerce.order.messaging.command;

// 오케스트레이터 -> payment (payment-commands 토픽)
// 보상: 재고 차감이 실패했을 때 이미 한 결제를 환불
public record RefundPaymentCommand(
        Long orderId
) {
}