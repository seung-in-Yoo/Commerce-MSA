package com.commerce.order.domain;

// product-service가 재고 차감에 성공하며 돌려준 상품 정보(이름,단가) 스냅샷
public record ProductSnapshot(
        Long productId,
        String productName,
        long unitPrice
) {
}