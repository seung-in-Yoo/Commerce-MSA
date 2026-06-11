package com.commerce.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

// 재고 차감 요청
public record StockDeductRequest(
        @NotEmpty @Valid List<Line> items
) {
    public record Line(
            @NotNull Long productId,
            @Positive int quantity
    ) {
    }
}