package com.commerce.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Order 도메인 단위 테스트")
class OrderTest {

    // 키보드 2개, 마우스 1개 PENDING 주문
    private Order pendingOrderWithTwoItems() {
        Order order = Order.create(1L);
        order.addItem(100L, 2);
        order.addItem(200L, 1);
        return order;
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("성공 - 생성 직후 PENDING, total 0, 이름/단가 비어있음")
        void success() {
            Order order = pendingOrderWithTwoItems();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(order.getTotalAmount()).isZero();
            assertThat(order.getItems()).hasSize(2)
                    .extracting(OrderItem::getProductName).containsOnlyNulls();
        }
    }

    @Nested
    @DisplayName("confirm")
    class Confirm {

        @Test
        @DisplayName("성공 - 스냅샷으로 이름/단가 채우고 total 재계산 후 CONFIRMED")
        void success() {
            Order order = pendingOrderWithTwoItems();

            order.confirm(List.of(
                    new ProductSnapshot(100L, "키보드", 30000L),
                    new ProductSnapshot(200L, "마우스", 15000L)));

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getTotalAmount()).isEqualTo(75000L);
            assertThat(order.getItems()).extracting(OrderItem::getProductName)
                    .containsExactly("키보드", "마우스");
            assertThat(order.getItems()).extracting(OrderItem::getUnitPrice)
                    .containsExactly(30000L, 15000L);
        }

        @Test
        @DisplayName("성공 - 스냅샷에 없는 항목은 이름/단가가 그대로 비어 total에서 0 처리")
        void partialSnapshot() {
            Order order = pendingOrderWithTwoItems();

            order.confirm(List.of(new ProductSnapshot(100L, "키보드", 30000L)));

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getTotalAmount()).isEqualTo(60000L);
        }
    }

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        @DisplayName("성공 - CANCELLED로 전이, total은 0 유지")
        void success() {
            Order order = pendingOrderWithTwoItems();

            order.cancel();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getTotalAmount()).isZero();
        }
    }
}