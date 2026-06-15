package com.commerce.order.service;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.ProductSnapshot;
import com.commerce.order.dto.CreateOrderRequest;
import com.commerce.order.dto.OrderResponse;
import com.commerce.order.exception.OrderErrorCase;
import com.commerce.order.global.exception.ApplicationException;
import com.commerce.order.messaging.OrderEventPublisher;
import com.commerce.order.messaging.event.OrderCreatedEvent;
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
    private final OrderEventPublisher orderEventPublisher;

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        // product를 동기 호출하지 않는다
        // 주문은 자기가 아는 것(productId, quantity)만으로 먼저 생성하고,
        // 재고 차감은 OrderCreated 이벤트로 product-service에 비동기로 위임
        Order order = Order.create(request.customerId());
        request.items().forEach(line -> order.addItem(line.productId(), line.quantity()));
        Order saved = orderRepository.save(order);

        // 재고 차감을 이벤트로 위임 -> product가 해당 토픽을 구독해 비동기로 재고를 깎음
        OrderCreatedEvent event = new OrderCreatedEvent(
                saved.getId(),
                saved.getCustomerId(),
                saved.getItems().stream()
                        .map(item -> new OrderCreatedEvent.Item(item.getProductId(), item.getQuantity()))
                        .toList());
        orderEventPublisher.publishOrderCreated(event);

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