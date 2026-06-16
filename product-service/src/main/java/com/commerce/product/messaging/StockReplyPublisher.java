package com.commerce.product.messaging;

import com.commerce.product.messaging.reply.StockProcessedReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// 재고 차감 결과를 stock-replies 토픽으로 발행
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReplyPublisher {

    public static final String STOCK_REPLIES_TOPIC = "stock-replies";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishStockProcessed(StockProcessedReply reply) {
        // key=orderId -> 같은 주문의 reply는 같은 파티션으로 가서 순서 보장
        kafkaTemplate.send(STOCK_REPLIES_TOPIC, String.valueOf(reply.orderId()), reply);
        log.info("[product] StockProcessed 응답 발행 -> topic={}, orderId={}, result={}",
                STOCK_REPLIES_TOPIC, reply.orderId(), reply.result());
    }
}