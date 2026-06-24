package com.commerce.payment.messaging;

import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.dto.PaymentResponse;
import com.commerce.payment.messaging.command.ProcessPaymentCommand;
import com.commerce.payment.messaging.inbox.ProcessedMessage;
import com.commerce.payment.messaging.inbox.ProcessedMessageRepository;
import com.commerce.payment.messaging.reply.PaymentProcessedReply;
import com.commerce.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// payment-commands 토픽 구독자
// 오케스트레이터의 ProcessPaymentCommand를 받아 결제를 시도하고, 결과를 payment-replies로 응답
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCommandListener {

    private final PaymentService paymentService;
    private final PaymentReplyPublisher paymentReplyPublisher;
    private final ProcessedMessageRepository processedMessageRepository;
    private final ProcessingDelay processingDelay;

    // payment가 두 command 타입(결제/환불)을 구독하므로 타입별 전용 팩토리 지정
    // 결제 저장과 inbox 기록(ProcessedMessage)을 한 트랜잭션으로 묶음
    @KafkaListener(topics = "payment-commands", containerFactory = "processPaymentCommandListenerFactory")
    @Transactional
    public void onProcessPayment(ProcessPaymentCommand command) {
        log.info("[payment] ProcessPayment 명령 수신 <- messageId={}, orderId={}, amount={}",
                command.messageId(), command.orderId(), command.amount());

        // 멱등 가드 -> 이미 처리한 messageId면 결제하지 않고 스킵
        if (processedMessageRepository.existsById(command.messageId())) {
            log.info("[payment] 중복 메시지 스킵(이미 처리됨) -> messageId={}, orderId={}",
                    command.messageId(), command.orderId());
            return;
        }

        // 병목 재현 -> 현실적인 결제 처리시간(외부 PG 승인 등) 인위적 지연
        // 기본값 0이라 평소엔 무영향 -> 부하실험 때만 PAYMENT_PROCESSING_DELAY_MS로 켠다
        processingDelay.apply();

        PaymentResponse payment = paymentService.pay(command.orderId(), command.amount());
        processedMessageRepository.save(ProcessedMessage.of(command.messageId()));

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