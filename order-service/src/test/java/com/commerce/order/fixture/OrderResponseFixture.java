package com.commerce.order.fixture;

import com.commerce.order.domain.OrderStatus;
import com.commerce.order.dto.OrderItemResponse;
import com.commerce.order.dto.OrderResponse;

import java.time.LocalDateTime;
import java.util.List;

public class OrderResponseFixture {

    public static OrderResponse defaultResponse() {
        return OrderResponse.builder()
                .orderId(1L)
                .customerId(1L)
                .status(OrderStatus.CONFIRMED)
                .totalAmount(660000L)
                .items(List.of(
                        OrderItemResponse.builder()
                                .productId(1L).productName("키보드").unitPrice(30000L).quantity(2).lineTotal(60000L).build(),
                        OrderItemResponse.builder()
                                .productId(3L).productName("컴퓨터").unitPrice(600000L).quantity(1).lineTotal(600000L).build()))
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
    }
}