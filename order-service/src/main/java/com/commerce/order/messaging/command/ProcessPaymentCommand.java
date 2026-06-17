package com.commerce.order.messaging.command;

import java.util.UUID;

// 오케스트레이터 -> payment (payment-commands 토픽)
// payment가 항목(items)이나 재고를 알 필요가 없기 때문에 금액만 넘긴다
// messageId: 이 명령 1건의 고유 식별자 (컨슈머가 inbox로 중복 배달을 걸러냄)
public record ProcessPaymentCommand(
        String messageId,
        Long orderId,
        long amount
) {
    // 발행 시점에 messageId를 새로 찍어 명령을 만듬
    public static ProcessPaymentCommand create(Long orderId, long amount) {
        return new ProcessPaymentCommand(UUID.randomUUID().toString(), orderId, amount);
    }
}