package com.commerce.order.messaging;

import com.commerce.order.domain.Order;
import com.commerce.order.exception.OrderErrorCase;
import com.commerce.order.global.exception.ApplicationException;
import com.commerce.order.messaging.command.DeductStockCommand;
import com.commerce.order.messaging.command.ProcessPaymentCommand;
import com.commerce.order.messaging.reply.PaymentProcessedReply;
import com.commerce.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 주문 → 결제 → 재고 사가의 중앙 오케스트레이터
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSagaOrchestrator {

    private final SagaCommandPublisher commandPublisher;
    private final OrderRepository orderRepository;

    // 사가 시작 -> 생성된 주문(PENDING)의 금액으로 결제를 명령
    public void start(Order order) {
        log.info("[order] 사가 시작 -> 결제 명령 결정 orderId={}, amount={}",
                order.getId(), order.getTotalAmount());
        commandPublisher.sendProcessPayment(new ProcessPaymentCommand(order.getId(), order.getTotalAmount()));
    }

    // 결제 응답 수신 -> 다음 단계 결정
    // APPROVED : 재고 차감 명령
    // FAILED   : 주문 취소
    @KafkaListener(topics = "payment-replies", containerFactory = "paymentProcessedReplyListenerFactory")
    @Transactional
    public void onPaymentReply(PaymentProcessedReply reply) {
        log.info("[order] PaymentProcessed 응답 수신 <- orderId={}, result={}", reply.orderId(), reply.result());
        Order order = findOrder(reply.orderId());

        switch (reply.result()) {
            case APPROVED -> {
                List<DeductStockCommand.Item> items = order.getItems().stream()
                        .map(item -> new DeductStockCommand.Item(item.getProductId(), item.getQuantity()))
                        .toList();
                commandPublisher.sendDeductStock(new DeductStockCommand(order.getId(), items));
                log.info("[order] 결제 승인 -> 재고 차감 명령 결정 orderId={}", order.getId());
            }
            case FAILED -> {
                order.cancel();
                log.warn("[order] 결제 거절 -> 주문 취소(CANCELLED) orderId={}, reason={}",
                        order.getId(), reply.reasonCode());
            }
        }
    }

    private Order findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> ApplicationException.from(OrderErrorCase.ORDER_NOT_FOUND));
    }
}