package com.commerce.order.dto;

import com.commerce.order.domain.OrderItem;

public record OrderItemResponse(
        Long productId,
        String productName,
        long unitPrice,
        int quantity,
        long lineTotal
) {
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getProductId(),
                item.getProductName(),
                item.getUnitPrice(),
                item.getQuantity(),
                item.getLineTotal()
        );
    }
}