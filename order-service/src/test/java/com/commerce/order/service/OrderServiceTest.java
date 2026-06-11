package com.commerce.order.service;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.dto.OrderItemResponse;
import com.commerce.order.dto.OrderResponse;
import com.commerce.order.exception.OrderErrorCase;
import com.commerce.order.fixture.OrderRequestFixture;
import com.commerce.order.fixture.StockDeductApiResponseFixture;
import com.commerce.order.global.client.ProductClient;
import com.commerce.order.global.exception.ApplicationException;
import com.commerce.order.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderService 단위 테스트")
class OrderServiceTest {

    @InjectMocks
    private OrderService orderService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductClient productClient;

    @Nested
    @DisplayName("createOrder")
    class CreateOrder {

        @Test
        @DisplayName("성공 - product가 돌려준 이름/가격으로 주문 구성, 재고차감 호출 1회")
        void success() {
            given(productClient.deductStock(any())).willReturn(StockDeductApiResponseFixture.defaultItems());
            given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));

            OrderResponse response = orderService.createOrder(OrderRequestFixture.defaultCreateRequest());

            assertThat(response.getCustomerId()).isEqualTo(1L);
            assertThat(response.getStatus()).isEqualTo(OrderStatus.CREATED);
            assertThat(response.getTotalAmount()).isEqualTo(660000L);
            assertThat(response.getItems()).hasSize(2)
                    .extracting(OrderItemResponse::getProductName).containsExactly("키보드", "컴퓨터");
            then(productClient).should().deductStock(any());
            then(orderRepository).should().save(any(Order.class));
        }

        @Test
        @DisplayName("실패 - product 호출 실패(재고부족 등)는 그대로 전파, 주문 저장 안 함")
        void productCallFails() {
            given(productClient.deductStock(any()))
                    .willThrow(ApplicationException.from(OrderErrorCase.PRODUCT_OUT_OF_STOCK));

            assertThatThrownBy(() -> orderService.createOrder(OrderRequestFixture.defaultCreateRequest()))
                    .isInstanceOf(ApplicationException.class)
                    .extracting(e -> ((ApplicationException) e).getErrorCase())
                    .isEqualTo(OrderErrorCase.PRODUCT_OUT_OF_STOCK);
            then(orderRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("실패 - product 서비스 장애(503)도 그대로 전파")
        void productUnavailable() {
            given(productClient.deductStock(any()))
                    .willThrow(ApplicationException.from(OrderErrorCase.PRODUCT_SERVICE_UNAVAILABLE));

            assertThatThrownBy(() -> orderService.createOrder(OrderRequestFixture.defaultCreateRequest()))
                    .isInstanceOf(ApplicationException.class)
                    .extracting(e -> ((ApplicationException) e).getErrorCase())
                    .isEqualTo(OrderErrorCase.PRODUCT_SERVICE_UNAVAILABLE);
            then(orderRepository).should(never()).save(any());
        }
    }

    @Nested
    @DisplayName("getOrder")
    class GetOrder {

        @Test
        @DisplayName("성공 - 주문 조회")
        void success() {
            Order order = mock(Order.class);
            given(order.getId()).willReturn(1L);
            given(order.getCustomerId()).willReturn(1L);
            given(order.getStatus()).willReturn(OrderStatus.CREATED);
            given(order.getTotalAmount()).willReturn(60000L);
            given(order.getItems()).willReturn(List.of());
            given(orderRepository.findById(1L)).willReturn(Optional.of(order));

            OrderResponse response = orderService.getOrder(1L);

            assertThat(response.getOrderId()).isEqualTo(1L);
            assertThat(response.getTotalAmount()).isEqualTo(60000L);
        }

        @Test
        @DisplayName("실패 - 존재하지 않는 주문 → ORDER_NOT_FOUND")
        void notFound() {
            given(orderRepository.findById(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.getOrder(99L))
                    .isInstanceOf(ApplicationException.class)
                    .extracting(e -> ((ApplicationException) e).getErrorCase())
                    .isEqualTo(OrderErrorCase.ORDER_NOT_FOUND);
        }
    }
}