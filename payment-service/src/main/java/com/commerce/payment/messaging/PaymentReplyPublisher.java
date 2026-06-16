package com.commerce.payment.messaging;

import com.commerce.payment.messaging.reply.PaymentProcessedReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// 결제 결과를 payment-replies 토픽으로 발행
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReplyPublisher {

    public static final String PAYMENT_REPLIES_TOPIC = "payment-replies";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishPaymentProcessed(PaymentProcessedReply reply) {
        // key=orderId -> 같은 주문의 reply는 같은 파티션으로 가서 순서 보장
        kafkaTemplate.send(PAYMENT_REPLIES_TOPIC, String.valueOf(reply.orderId()), reply);
        log.info("[payment] PaymentProcessed 응답 발행 -> topic={}, orderId={}, result={}",
                PAYMENT_REPLIES_TOPIC, reply.orderId(), reply.result());
    }
}