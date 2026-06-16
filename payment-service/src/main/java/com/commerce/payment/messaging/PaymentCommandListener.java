package com.commerce.payment.messaging;

import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.dto.PaymentResponse;
import com.commerce.payment.messaging.command.ProcessPaymentCommand;
import com.commerce.payment.messaging.reply.PaymentProcessedReply;
import com.commerce.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// payment-commands 토픽 구독자
// 오케스트레이터의 ProcessPaymentCommand를 받아 결제를 시도하고, 결과를 payment-replies로 응답
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCommandListener {

    private final PaymentService paymentService;
    private final PaymentReplyPublisher paymentReplyPublisher;

    // payment가 두 command 타입(결제/환불)을 구독하므로 타입별 전용 팩토리 지정
    @KafkaListener(topics = "payment-commands", containerFactory = "processPaymentCommandListenerFactory")
    public void onProcessPayment(ProcessPaymentCommand command) {
        log.info("[payment] ProcessPayment 명령 수신 <- orderId={}, amount={}",
                command.orderId(), command.amount());

        PaymentResponse payment = paymentService.pay(command.orderId(), command.amount());

        if (payment.getStatus() == PaymentStatus.APPROVED) {
            paymentReplyPublisher.publishPaymentProcessed(
                    PaymentProcessedReply.approved(command.orderId(), payment.getPaymentId()));
            log.info("[payment] 결제 승인(APPROVED) -> orderId={}, paymentId={}",
                    command.orderId(), payment.getPaymentId());
        } else {
            paymentReplyPublisher.publishPaymentProcessed(
                    PaymentProcessedReply.failed(command.orderId(), payment.getPaymentId(), "PAYMENT_LIMIT_EXCEEDED"));
            log.warn("[payment] 결제 거절(FAILED) -> orderId={}, amount={}",
                    command.orderId(), command.amount());
        }
    }
}