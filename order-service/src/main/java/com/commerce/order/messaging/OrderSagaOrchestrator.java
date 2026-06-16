package com.commerce.order.messaging;

import com.commerce.order.domain.Order;
import com.commerce.order.messaging.command.ProcessPaymentCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 주문 → 결제 → 재고 사가의 중앙 오케스트레이터
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSagaOrchestrator {

    private final SagaCommandPublisher commandPublisher;

    // 사가 시작 -> 생성된 주문(PENDING)의 금액으로 결제를 명령
    public void start(Order order) {
        log.info("[order] 사가 시작 -> 결제 명령 결정 orderId={}, amount={}",
                order.getId(), order.getTotalAmount());
        commandPublisher.sendProcessPayment(new ProcessPaymentCommand(order.getId(), order.getTotalAmount()));
    }
}