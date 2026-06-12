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

    // 주문 생성 시점엔 product 소유 데이터(이름/가격)를 모름 → null/0으로 둠
    // 동기 호출을 없애고 비동기 이벤트로 전환
    @Column
    private String productName;

    @Column(nullable = false)
    private long unitPrice;

    @Column(nullable = false)
    private int quantity;

    private OrderItem(Order order, Long productId, int quantity) {
        this.order = order;
        this.productId = productId;
        this.productName = null;
        this.unitPrice = 0L;
        this.quantity = quantity;
    }

    static OrderItem of(Order order, Long productId, int quantity) {
        return new OrderItem(order, productId, quantity);
    }

    public long getLineTotal() {
        return unitPrice * quantity;
    }
}