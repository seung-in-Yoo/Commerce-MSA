package com.commerce.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 상품 도메인 product-service가 소유
// 이름/가격: order-service는 해당 값을 물어봐서 받아감 (직접 JOIN 불가)
// 재고: 주문 생성 시 order-service의 동기 호출로 차감
@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private long price;

    @Column(nullable = false)
    private int stockQuantity;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private Product(String name, long price, int stockQuantity) {
        this.name = name;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.createdAt = LocalDateTime.now();
    }

    public static Product create(String name, long price, int stockQuantity) {
        return new Product(name, price, stockQuantity);
    }
}