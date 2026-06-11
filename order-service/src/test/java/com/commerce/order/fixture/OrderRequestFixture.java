package com.commerce.order.fixture;

import com.commerce.order.dto.CreateOrderRequest;
import com.commerce.order.dto.OrderLineRequest;

import java.util.List;

public class OrderRequestFixture {

    public static CreateOrderRequest defaultCreateRequest() {
        return new CreateOrderRequest(1L, List.of(
                new OrderLineRequest(1L, 2),
                new OrderLineRequest(3L, 1)));
    }

    public static CreateOrderRequest createRequest(Long customerId, List<OrderLineRequest> items) {
        return new CreateOrderRequest(customerId, items);
    }

    public static CreateOrderRequest emptyItemsRequest() {
        return new CreateOrderRequest(1L, List.of());
    }
}