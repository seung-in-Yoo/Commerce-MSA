package com.commerce.order.messaging;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.ProductSnapshot;
import com.commerce.order.exception.OrderErrorCase;
import com.commerce.order.global.exception.ApplicationException;
import com.commerce.order.messaging.command.DeductStockCommand;
import com.commerce.order.messaging.command.ProcessPaymentCommand;
import com.commerce.order.messaging.command.RefundPaymentCommand;
import com.commerce.order.messaging.reply.PaymentProcessedReply;
import com.commerce.order.messaging.reply.StockProcessedReply;
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

    // 재고 응답 수신 -> 사가의 마지막 단계 결정
    // DEDUCTED : product가 준 이름/단가 스냅샷으로 주문 확정(CONFIRMED) -> 사가 성공 종료
    // FAILED   : 이미 한 결제를 환불 명령 + 주문 취소(CANCELLED) -> 보상 후 종료
    @KafkaListener(topics = "stock-replies", containerFactory = "stockProcessedReplyListenerFactory")
    @Transactional
    public void onStockReply(StockProcessedReply reply) {
        log.info("[order] StockProcessed 응답 수신 <- orderId={}, result={}", reply.orderId(), reply.result());
        Order order = findOrder(reply.orderId());

        switch (reply.result()) {
            case DEDUCTED -> {
                List<ProductSnapshot> snapshots = reply.items().stream()
                        .map(item -> new ProductSnapshot(item.productId(), item.productName(), item.unitPrice()))
                        .toList();
                order.confirm(snapshots);
                log.info("[order] 재고 차감 성공 -> 주문 확정(CONFIRMED) orderId={}", order.getId());
            }
            case FAILED -> {
                // 보상: 이미 승인된 결제를 환불하라고 명령하고, 주문을 취소
                commandPublisher.sendRefundPayment(new RefundPaymentCommand(order.getId()));
                order.cancel();
                log.warn("[order] 재고 실패 -> 결제 환불 명령 + 주문 취소(CANCELLED) orderId={}, reason={}",
                        order.getId(), reply.reasonCode());
            }
        }
    }

    private Order findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> ApplicationException.from(OrderErrorCase.ORDER_NOT_FOUND));
    }
}