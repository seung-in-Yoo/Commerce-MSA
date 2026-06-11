package com.commerce.product.fixture;

import com.commerce.product.dto.ProductCreateRequest;
import com.commerce.product.dto.StockDeductRequest;

import java.util.List;

public class ProductRequestFixture {

    public static ProductCreateRequest defaultCreateRequest() {
        return new ProductCreateRequest("키보드", 30000L, 10);
    }

    public static ProductCreateRequest createRequest(String name, long price, int stockQuantity) {
        return new ProductCreateRequest(name, price, stockQuantity);
    }

    public static StockDeductRequest defaultDeductRequest() {
        return deductRequest(1L, 2);
    }

    public static StockDeductRequest deductRequest(Long productId, int quantity) {
        return new StockDeductRequest(List.of(new StockDeductRequest.Line(productId, quantity)));
    }
}