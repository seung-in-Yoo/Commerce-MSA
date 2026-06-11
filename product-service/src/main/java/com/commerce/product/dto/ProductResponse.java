package com.commerce.product.dto;

import com.commerce.product.domain.Product;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProductResponse {

    private final Long productId;
    private final String name;
    private final long price;
    private final int stockQuantity;

    public static ProductResponse from(Product product) {
        return ProductResponse.builder()
                .productId(product.getId())
                .name(product.getName())
                .price(product.getPrice())
                .stockQuantity(product.getStockQuantity())
                .build();
    }
}