package com.commerce.product.messaging;

import com.commerce.product.dto.StockDeductRequest;
import com.commerce.product.dto.StockDeductResponse;
import com.commerce.product.global.exception.ApplicationException;
import com.commerce.product.messaging.event.OrderCreatedEvent;
import com.commerce.product.messaging.event.StockProcessedEvent;
import com.commerce.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

// order-events 토픽 구독자
// OrderCreated를 받아 재고를 차감하고, 그 처리 결과(성공/실패)를 StockProcessed 이벤트로 되돌려보냄
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final ProductService productService;
    private final ProductEventPublisher productEventPublisher;

    @KafkaListener(topics = "order-events")
    public void onOrderCreated(OrderCreatedEvent event) {
        log.info("[product] OrderCreated 수신 <- orderId={}, items={}", event.orderId(), event.items());

        StockDeductRequest request = new StockDeductRequest(
                event.items().stream()
                        .map(item -> new StockDeductRequest.Line(item.productId(), item.quantity()))
                        .toList());

        try {
            StockDeductResponse response = productService.deductStock(request);
            // 차감 성공 -> 차감된 상품의 이름/단가를 실어 DEDUCTED 발행 -> order가 CONFIRMED로 확정
            productEventPublisher.publishStockProcessed(
                    StockProcessedEvent.deducted(event.orderId(), toEventItems(response)));
            log.info("[product] 재고 차감 완료 -> orderId={}", event.orderId());
        } catch (ApplicationException e) {
            // 차감 실패(재고 부족/상품 없음) -> 삼키지 않고 FAILED 발행 -> order가 CANCELLED로 보상
            log.warn("[product] 재고 차감 실패 -> orderId={}, code={} -> StockProcessed(FAILED) 발행",
                    event.orderId(), e.getErrorCase().getCode());
            productEventPublisher.publishStockProcessed(
                    StockProcessedEvent.failed(event.orderId(), e.getErrorCase().getCode()));
        }
    }

    private List<StockProcessedEvent.Item> toEventItems(StockDeductResponse response) {
        return response.getItems().stream()
                .map(item -> new StockProcessedEvent.Item(
                        item.getProductId(), item.getProductName(), item.getUnitPrice()))
                .toList();
    }
}