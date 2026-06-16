package com.commerce.order.messaging;

import com.commerce.order.messaging.command.DeductStockCommand;
import com.commerce.order.messaging.command.ProcessPaymentCommand;
import com.commerce.order.messaging.command.RefundPaymentCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// 오케스트레이터가 내리는 command를 Kafka로 발행
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaCommandPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendProcessPayment(ProcessPaymentCommand command) {
        kafkaTemplate.send(SagaTopics.PAYMENT_COMMANDS, String.valueOf(command.orderId()), command);
        log.info("[order] ProcessPayment 명령 발행 -> topic={}, orderId={}, amount={}",
                SagaTopics.PAYMENT_COMMANDS, command.orderId(), command.amount());
    }

    public void sendDeductStock(DeductStockCommand command) {
        kafkaTemplate.send(SagaTopics.STOCK_COMMANDS, String.valueOf(command.orderId()), command);
        log.info("[order] DeductStock 명령 발행 -> topic={}, orderId={}",
                SagaTopics.STOCK_COMMANDS, command.orderId());
    }

    // 재고 실패 시 이미 한 결제를 환불하라는 명령 발행
    public void sendRefundPayment(RefundPaymentCommand command) {
        kafkaTemplate.send(SagaTopics.PAYMENT_REFUND_COMMANDS, String.valueOf(command.orderId()), command);
        log.info("[order] RefundPayment 명령 발행 -> topic={}, orderId={}",
                SagaTopics.PAYMENT_REFUND_COMMANDS, command.orderId());
    }
}