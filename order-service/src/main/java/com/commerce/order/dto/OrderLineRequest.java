package com.commerce.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record OrderLineRequest(
        @NotNull Long productId,
        @NotBlank String productName,
        @Positive long unitPrice,
        @Positive int quantity
) {
}