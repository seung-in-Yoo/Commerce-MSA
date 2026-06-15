package com.commerce.product.messaging;

import com.commerce.product.messaging.event.StockProcessedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// 재고 처리 결과 이벤트 발행자 -> product-events 토픽 (order가 구독)
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventPublisher {

    public static final String PRODUCT_EVENTS_TOPIC = "product-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishStockProcessed(StockProcessedEvent event) {
        // key=orderId -> 같은 주문의 결과 이벤트는 같은 파티션으로 가서 순서 보장
        kafkaTemplate.send(PRODUCT_EVENTS_TOPIC, String.valueOf(event.orderId()), event);
        log.info("[product] StockProcessed 발행 -> topic={}, orderId={}, result={}",
                PRODUCT_EVENTS_TOPIC, event.orderId(), event.result());
    }
}