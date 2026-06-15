package com.commerce.order.messaging;

import com.commerce.order.messaging.event.PaymentProcessedEvent;
import com.commerce.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// payment-events 토픽 구독자
// 결제 결과를 받아 결제가 거절된 주문을 즉시 취소
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentResultListener {

    private final OrderService orderService;

    @KafkaListener(topics = "payment-events", containerFactory = "paymentProcessedListenerFactory")
    public void onPaymentProcessed(PaymentProcessedEvent event) {
        log.info("[order] PaymentProcessed 수신 <- orderId={}, result={}", event.orderId(), event.result());

        if (event.result() == PaymentProcessedEvent.Result.FAILED) {
            // 결제 거절 -> 재고 단계로 가지 못하므로 주문을 즉시 취소
            orderService.cancelOrder(event.orderId());
            log.warn("[order] 결제 거절로 주문 취소(CANCELLED) -> orderId={}, reason={}",
                    event.orderId(), event.reasonCode());
        }
    }
}