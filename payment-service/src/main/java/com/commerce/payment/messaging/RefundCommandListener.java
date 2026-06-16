package com.commerce.payment.messaging;

import com.commerce.payment.messaging.command.RefundPaymentCommand;
import com.commerce.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// payment-refund-commands 토픽 구독자
// 오케스트레이터의 보상을 받아 이미 한 결제를 환불
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundCommandListener {

    private final PaymentService paymentService;

    @KafkaListener(topics = "payment-refund-commands", containerFactory = "refundPaymentCommandListenerFactory")
    public void onRefundPayment(RefundPaymentCommand command) {
        log.info("[payment] RefundPayment 명령 수신 <- orderId={}", command.orderId());
        paymentService.refund(command.orderId());
        log.warn("[payment] 결제 환불(REFUNDED) -> orderId={}", command.orderId());
    }
}