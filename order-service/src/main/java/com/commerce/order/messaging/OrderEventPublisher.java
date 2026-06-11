package com.commerce.order.messaging;

import com.commerce.order.messaging.event.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// 주문 도메인 이벤트 발행자
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    public static final String ORDER_EVENTS_TOPIC = "order-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishOrderCreated(OrderCreatedEvent event) {
        // key를 orderId로 → 같은 주문의 이벤트들은 같은 파티션으로 가서 순서 보장
        kafkaTemplate.send(ORDER_EVENTS_TOPIC, String.valueOf(event.orderId()), event);
        log.info("[order] OrderCreated 발행 → topic={}, orderId={}", ORDER_EVENTS_TOPIC, event.orderId());
    }
}