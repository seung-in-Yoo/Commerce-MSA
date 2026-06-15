package com.commerce.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false)
    private long totalAmount;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private Order(Long customerId) {
        this.customerId = customerId;
        this.status = OrderStatus.PENDING;   // 재고 차감 결과를 기다리는 Saga 시작 상태
        this.totalAmount = 0L;
        this.createdAt = LocalDateTime.now();
    }

    public static Order create(Long customerId) {
        return new Order(customerId);
    }

    public void addItem(Long productId, int quantity) {
        OrderItem item = OrderItem.of(this, productId, quantity);
        this.items.add(item);
        this.totalAmount += item.getLineTotal();   // 생성 시점엔 단가 0이라 0 누적
    }

    // 재고 차감 성공 수신 시 product가 준 이름/단가를 각 항목에 채우고 총액을 다시 계산한 뒤 주문을 확정
    public void confirm(List<ProductSnapshot> snapshots) {
        snapshots.forEach(snapshot -> items.stream()
                .filter(item -> item.getProductId().equals(snapshot.productId()))
                .forEach(item -> item.applyProductInfo(snapshot.productName(), snapshot.unitPrice())));
        recalculateTotal();
        this.status = OrderStatus.CONFIRMED;
    }

    // 재고 차감 실패 수신 시 주문을 취소
    // PENDING 주문을 무효화하는 보상 트랜잭션의 order 결과
    public void cancel() {
        this.status = OrderStatus.CANCELLED;
    }

    private void recalculateTotal() {
        this.totalAmount = items.stream().mapToLong(OrderItem::getLineTotal).sum();
    }
}