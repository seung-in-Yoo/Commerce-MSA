package com.commerce.payment.messaging;

import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.dto.PaymentResponse;
import com.commerce.payment.messaging.event.OrderCreatedEvent;
import com.commerce.payment.messaging.event.PaymentProcessedEvent;
import com.commerce.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

// order-events 토픽 구독자
// OrderCreated를 받아 결제를 시도하고, 그 결과(승인/거절)를 PaymentProcessed로 발행
// 결제가 재고보다 먼저이므로, 승인 시 재고 차감에 필요한 items를 결과 이벤트에 실어 다음 단계로 넘김
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final PaymentService paymentService;
    private final PaymentEventPublisher paymentEventPublisher;

    @KafkaListener(topics = "order-events", containerFactory = "orderCreatedListenerFactory")
    public void onOrderCreated(OrderCreatedEvent event) {
        log.info("[payment] OrderCreated 수신 <- orderId={}, amount={}", event.orderId(), event.amount());

        PaymentResponse payment = paymentService.pay(event.orderId(), event.amount());

        if (payment.getStatus() == PaymentStatus.APPROVED) {
            // 승인 -> 다음 단계(재고 차감)로 items를 실어 보냄 -> product가 구독해 차감
            paymentEventPublisher.publishPaymentProcessed(PaymentProcessedEvent.approved(
                    event.orderId(), payment.getPaymentId(), payment.getAmount(), toItems(event.items())));
            log.info("[payment] 결제 승인(APPROVED) -> orderId={}, paymentId={}",
                    event.orderId(), payment.getPaymentId());
        } else {
            // 거절 -> 재고는 건드리지 않고 FAILED 발행 -> order가 구독해 주문 즉시 취소(보상)
            paymentEventPublisher.publishPaymentProcessed(PaymentProcessedEvent.failed(
                    event.orderId(), payment.getPaymentId(), payment.getAmount(), "PAYMENT_LIMIT_EXCEEDED"));
            log.warn("[payment] 결제 거절(FAILED) -> orderId={}, amount={} -> PaymentProcessed(FAILED) 발행",
                    event.orderId(), event.amount());
        }
    }

    private List<PaymentProcessedEvent.Item> toItems(List<OrderCreatedEvent.Item> items) {
        return items.stream()
                .map(item -> new PaymentProcessedEvent.Item(item.productId(), item.quantity()))
                .toList();
    }
}