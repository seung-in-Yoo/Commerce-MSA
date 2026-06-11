package com.commerce.product.fixture;

import com.commerce.product.dto.ProductResponse;
import com.commerce.product.dto.StockDeductResponse;

import java.util.List;

public class ProductResponseFixture {

    public static ProductResponse defaultResponse() {
        return ProductResponse.builder()
                .productId(1L)
                .name("키보드")
                .price(30000L)
                .stockQuantity(8)
                .build();
    }

    public static StockDeductResponse defaultDeductResponse() {
        return StockDeductResponse.builder()
                .items(List.of(StockDeductResponse.Item.builder()
                        .productId(1L)
                        .productName("키보드")
                        .unitPrice(30000L)
                        .quantity(2)
                        .build()))
                .build();
    }
}