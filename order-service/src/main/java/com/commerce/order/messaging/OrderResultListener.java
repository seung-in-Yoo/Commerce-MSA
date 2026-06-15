package com.commerce.order.messaging;

import com.commerce.order.domain.ProductSnapshot;
import com.commerce.order.messaging.event.StockProcessedEvent;
import com.commerce.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

// product-events 토픽 구독자
// 재고 처리 결과(StockProcessed)를 받아 주문을 확정/취소한다
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderResultListener {

    private final OrderService orderService;

    @KafkaListener(topics = "product-events")
    public void onStockProcessed(StockProcessedEvent event) {
        log.info("[order] StockProcessed 수신 <- orderId={}, result={}", event.orderId(), event.result());

        switch (event.result()) {
            case DEDUCTED -> {
                // 차감 성공 -> product가 준 이름/단가 스냅샷으로 주문을 채우고 확정
                List<ProductSnapshot> snapshots = event.items().stream()
                        .map(item -> new ProductSnapshot(item.productId(), item.productName(), item.unitPrice()))
                        .toList();
                orderService.confirmOrder(event.orderId(), snapshots);
                log.info("[order] 주문 확정(CONFIRMED) -> orderId={}", event.orderId());
            }
            case FAILED -> {
                // 차감 실패 -> 주문 취소(보상)
                orderService.cancelOrder(event.orderId());
                log.warn("[order] 재고 실패로 주문 취소(CANCELLED) -> orderId={}, reason={}",
                        event.orderId(), event.reasonCode());
            }
        }
    }
}