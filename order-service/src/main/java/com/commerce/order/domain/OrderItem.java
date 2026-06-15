package com.commerce.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 주문 항목
@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false)
    private Long productId;

    // 이름은 product 소유라 주문 생성 시점엔 모름 → null로 두고, 재고 차감 후 product가 채움
    @Column
    private String productName;

    @Column(nullable = false)
    private long unitPrice;

    @Column(nullable = false)
    private int quantity;

    private OrderItem(Order order, Long productId, int quantity, long unitPrice) {
        this.order = order;
        this.productId = productId;
        this.productName = null;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    static OrderItem of(Order order, Long productId, int quantity, long unitPrice) {
        return new OrderItem(order, productId, quantity, unitPrice);
    }

    // product가 재고 차감 후 알려준 이름/단가를 주문 시점 스냅샷으로 채움
    void applyProductInfo(String productName, long unitPrice) {
        this.productName = productName;
        this.unitPrice = unitPrice;
    }

    public long getLineTotal() {
        return unitPrice * quantity;
    }
}