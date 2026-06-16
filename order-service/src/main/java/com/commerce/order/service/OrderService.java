package com.commerce.order.service;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.ProductSnapshot;
import com.commerce.order.dto.CreateOrderRequest;
import com.commerce.order.dto.OrderResponse;
import com.commerce.order.exception.OrderErrorCase;
import com.commerce.order.global.exception.ApplicationException;
import com.commerce.order.messaging.OrderSagaOrchestrator;
import com.commerce.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderSagaOrchestrator orchestrator;

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        // 주문은 자기가 productId, quantity, 예상 단가만으로 먼저 PENDING으로 저장
        Order order = Order.create(request.customerId());
        request.items().forEach(line -> order.addItem(line.productId(), line.quantity(), line.unitPrice()));
        Order saved = orderRepository.save(order);

        // 오케스트레이터가 사가를 직접 시작(결제부터 명령)
        orchestrator.start(saved);

        return OrderResponse.from(saved);
    }

    public OrderResponse getOrder(Long orderId) {
        return OrderResponse.from(findOrder(orderId));
    }

    // 재고 차감 성공(StockProcessed DEDUCTED) 수신 시 주문에 상품 스냅샷을 채우고 확정
    @Transactional
    public void confirmOrder(Long orderId, List<ProductSnapshot> snapshots) {
        Order order = findOrder(orderId);
        order.confirm(snapshots);
    }

    // 재고 차감 실패(StockProcessed FAILED) 수신 시 주문을 취소
    @Transactional
    public void cancelOrder(Long orderId) {
        Order order = findOrder(orderId);
        order.cancel();
    }

    private Order findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> ApplicationException.from(OrderErrorCase.ORDER_NOT_FOUND));
    }
}