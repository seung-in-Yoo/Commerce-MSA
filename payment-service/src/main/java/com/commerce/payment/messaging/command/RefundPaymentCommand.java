package com.commerce.payment.messaging.command;

// 오케스트레이터가 payment-refund-commands로 보내는 보상 명령의 payment측 사본
// messageId: 이 명령 1건의 고유 식별자 (inbox(ProcessedMessage)에 기록해 중복 배달을 거름)
public record RefundPaymentCommand(
        String messageId,
        Long orderId
) {
}