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
        order.addItem(100L, 2, 30000L);
        order.addItem(200L, 1, 15000L);
        return order;
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("성공 - 생성 직후 PENDING, 예상 단가로 total 산정, 이름은 비어있음")
        void success() {
            Order order = pendingOrderWithTwoItems();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
            // 결제가 재고보다 먼저라 생성 시점에 예상 단가로 결제용 총액으로
            assertThat(order.getTotalAmount()).isEqualTo(75000L);
            assertThat(order.getItems()).extracting(OrderItem::getUnitPrice)
                    .containsExactly(30000L, 15000L);
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
        @DisplayName("성공 - 스냅샷에 없는 항목은 예상 단가를 그대로 유지해 total에 반영")
        void partialSnapshot() {
            Order order = pendingOrderWithTwoItems();

            order.confirm(List.of(new ProductSnapshot(100L, "키보드", 30000L)));

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getTotalAmount()).isEqualTo(75000L);   // 30000*2 + 15000*1
        }
    }

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        @DisplayName("성공 - CANCELLED로 전이, 예상 총액은 그대로 유지")
        void success() {
            Order order = pendingOrderWithTwoItems();

            order.cancel();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getTotalAmount()).isEqualTo(75000L);
        }
    }
}