package com.commerce.payment.messaging.command;

// order(오케스트레이터)가 payment-commands로 보내는 결제 명령의 payment측 사본
public record ProcessPaymentCommand(
        Long orderId,
        long amount
) {
}