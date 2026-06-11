package com.commerce.order.service;

import com.commerce.order.domain.Order;
import com.commerce.order.dto.CreateOrderRequest;
import com.commerce.order.dto.OrderResponse;
import com.commerce.order.exception.OrderErrorCase;
import com.commerce.order.global.exception.ApplicationException;
import com.commerce.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        Order order = Order.create(request.customerId());
        request.items().forEach(line ->
                order.addItem(line.productId(), line.productName(), line.unitPrice(), line.quantity()));
        Order saved = orderRepository.save(order);
        return OrderResponse.from(saved);
    }

    public OrderResponse getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> ApplicationException.from(OrderErrorCase.ORDER_NOT_FOUND));
        return OrderResponse.from(order);
    }
}