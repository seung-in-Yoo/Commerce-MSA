package com.commerce.payment.messaging;

import com.commerce.payment.messaging.event.StockProcessedEvent;
import com.commerce.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// product-events 토픽 구독자
// 재고 차감 실패(StockProcessed FAILED)를 받으면, 이미 승인된 결제를 역순으로 되돌림(환불)
// 재고 차감 성공(DEDUCTED)이면 결제를 그대로 유지
@Slf4j
@Component
@RequiredArgsConstructor
public class StockResultListener {

    private final PaymentService paymentService;

    @KafkaListener(topics = "product-events", containerFactory = "stockProcessedListenerFactory")
    public void onStockProcessed(StockProcessedEvent event) {
        log.info("[payment] StockProcessed 수신 <- orderId={}, result={}", event.orderId(), event.result());

        if (event.result() == StockProcessedEvent.Result.FAILED) {
            // 재고 실패 -> 이미 한 결제를 환불(보상)
            paymentService.refund(event.orderId());
            log.warn("[payment] 재고 실패로 결제 환불(REFUNDED) -> orderId={}, reason={}",
                    event.orderId(), event.reasonCode());
        }
    }
}