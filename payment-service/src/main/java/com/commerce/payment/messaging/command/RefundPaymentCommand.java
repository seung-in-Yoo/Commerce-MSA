package com.commerce.payment.messaging.command;

// 오케스트레이터가 payment-refund-commands로 보내는 보상 명령의 payment측 사본
public record RefundPaymentCommand(
        Long orderId
) {
}