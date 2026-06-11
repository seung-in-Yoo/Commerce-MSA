package com.commerce.product.messaging;

import com.commerce.product.messaging.event.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// order-events 토픽 구독자
@Slf4j
@Component
public class OrderEventListener {

    @KafkaListener(topics = "order-events")
    public void onOrderCreated(OrderCreatedEvent event) {
        log.info("[product] OrderCreated 수신 ← orderId={}, customerId={}, items={}",
                event.orderId(), event.customerId(), event.items());
    }
}