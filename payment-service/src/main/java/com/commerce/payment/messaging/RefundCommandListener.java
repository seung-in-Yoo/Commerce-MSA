package com.commerce.payment.messaging;

import com.commerce.payment.messaging.command.RefundPaymentCommand;
import com.commerce.payment.messaging.inbox.ProcessedMessage;
import com.commerce.payment.messaging.inbox.ProcessedMessageRepository;
import com.commerce.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// payment-refund-commands 토픽 구독자
// 오케스트레이터의 보상을 받아 이미 한 결제를 환불
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundCommandListener {

    private final PaymentService paymentService;
    private final ProcessedMessageRepository processedMessageRepository;

    // 환불(refund)과 inbox 기록(ProcessedMessage)을 한 트랜잭션으로
    @KafkaListener(topics = "payment-refund-commands", containerFactory = "refundPaymentCommandListenerFactory")
    @Transactional
    public void onRefundPayment(RefundPaymentCommand command) {
        log.info("[payment] RefundPayment 명령 수신 <- messageId={}, orderId={}",
                command.messageId(), command.orderId());

        // 멱등 가드: 이미 처리한 messageId면 환불하지 않고 스킵
        if (processedMessageRepository.existsById(command.messageId())) {
            log.info("[payment] 중복 메시지 스킵(이미 처리됨) -> messageId={}, orderId={}",
                    command.messageId(), command.orderId());
            return;
        }

        paymentService.refund(command.orderId());
        processedMessageRepository.save(ProcessedMessage.of(command.messageId()));
        log.warn("[payment] 결제 환불(REFUNDED) -> orderId={}", command.orderId());
    }
}