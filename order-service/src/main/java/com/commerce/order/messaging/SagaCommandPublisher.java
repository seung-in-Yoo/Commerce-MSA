package com.commerce.order.messaging;

import com.commerce.order.messaging.command.DeductStockCommand;
import com.commerce.order.messaging.command.ProcessPaymentCommand;
import com.commerce.order.messaging.command.RefundPaymentCommand;
import com.commerce.order.messaging.outbox.OutboxMessage;
import com.commerce.order.messaging.outbox.OutboxMessageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 오케스트레이터가 내리는 command를 Kafka로 직접 발행하지 않고 outbox 테이블에 적재
// 실제 Kafka 발행은 별도 릴레이가 outbox의 PENDING 행을 읽어 처리
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaCommandPublisher {

    private final OutboxMessageRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public void sendProcessPayment(ProcessPaymentCommand command) {
        enqueue(SagaTopics.PAYMENT_COMMANDS, command.orderId(), command);
        log.info("[order] ProcessPayment 명령 outbox 적재 -> topic={}, orderId={}, amount={}",
                SagaTopics.PAYMENT_COMMANDS, command.orderId(), command.amount());
    }

    public void sendDeductStock(DeductStockCommand command) {
        enqueue(SagaTopics.STOCK_COMMANDS, command.orderId(), command);
        log.info("[order] DeductStock 명령 outbox 적재 -> topic={}, orderId={}",
                SagaTopics.STOCK_COMMANDS, command.orderId());
    }

    // 재고 실패 시 이미 한 결제를 환불하라는 명령 적재
    public void sendRefundPayment(RefundPaymentCommand command) {
        enqueue(SagaTopics.PAYMENT_REFUND_COMMANDS, command.orderId(), command);
        log.info("[order] RefundPayment 명령 outbox 적재 -> topic={}, orderId={}",
                SagaTopics.PAYMENT_REFUND_COMMANDS, command.orderId());
    }

    // 토픽/키/직렬화된 본문으로 outbox 행 한 건을 만들어 저장
    private void enqueue(String topic, Long orderId, Object command) {
        String payload = serialize(command);
        outboxRepository.save(OutboxMessage.create(topic, String.valueOf(orderId), payload));
    }

    private String serialize(Object command) {
        try {
            return objectMapper.writeValueAsString(command);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("outbox 메시지 직렬화 실패: " + command, e);
        }
    }
}