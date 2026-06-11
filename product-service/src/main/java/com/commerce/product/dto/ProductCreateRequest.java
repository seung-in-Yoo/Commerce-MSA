package com.commerce.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

// 상품 등록 요청
public record ProductCreateRequest(
        @NotBlank String name,
        @Positive long price,
        @PositiveOrZero int stockQuantity
) {
}