package com.commerce.order.messaging.command;

// 오케스트레이터 -> payment (payment-commands 토픽)
// payment가 항목(items)이나 재고를 알 필요가 없기 때문에 금액만 넘긴다
public record ProcessPaymentCommand(
        Long orderId,
        long amount
) {
}