package com.commerce.product.messaging;

import com.commerce.product.dto.StockDeductRequest;
import com.commerce.product.global.exception.ApplicationException;
import com.commerce.product.messaging.event.OrderCreatedEvent;
import com.commerce.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// order-events 토픽 구독자
// OrderCreated를 받아 실제 재고 차감을 수행 (order-service의 동기 REST 차감을 해당 비동기 consumer가 대체)
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final ProductService productService;

    @KafkaListener(topics = "order-events")
    public void onOrderCreated(OrderCreatedEvent event) {
        log.info("[product] OrderCreated 수신 ← orderId={}, items={}", event.orderId(), event.items());

        StockDeductRequest request = new StockDeductRequest(
                event.items().stream()
                        .map(item -> new StockDeductRequest.Line(item.productId(), item.quantity()))
                        .toList());

        try {
            productService.deductStock(request);
            log.info("[product] 재고 차감 완료 ← orderId={}", event.orderId());
        } catch (ApplicationException e) {
            // 주문은 이미 order-service에 CREATED로 저장돼 있는데, 여기서 재고 차감이 실패할 수 있음(재고 부족/상품 없음)
            log.error("[product] 재고 차감 실패 ← orderId={}, code={}, msg={} → 주문-재고 불일치 발생",
                    event.orderId(), e.getErrorCase().getCode(), e.getErrorCase().getMessage());
        }
    }
}