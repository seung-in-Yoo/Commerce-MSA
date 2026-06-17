package com.commerce.payment.messaging.command;

// order(오케스트레이터)가 payment-commands로 보내는 결제 명령의 payment측 사본
// messageId: 이 명령 1건의 고유 식별자 (ProcessedMessage에 기록해 중복 배달을 거름)
public record ProcessPaymentCommand(
        String messageId,
        Long orderId,
        long amount
) {
}