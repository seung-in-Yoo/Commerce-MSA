package com.commerce.order.dto;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class OrderResponse {

    private final Long orderId;
    private final Long customerId;
    private final OrderStatus status;
    private final long totalAmount;
    private final List<OrderItemResponse> items;
    private final LocalDateTime createdAt;

    public static OrderResponse from(Order order) {
        return OrderResponse.builder()
                .orderId(order.getId())
                .customerId(order.getCustomerId())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .items(order.getItems().stream().map(OrderItemResponse::from).toList())
                .createdAt(order.getCreatedAt())
                .build();
    }
}