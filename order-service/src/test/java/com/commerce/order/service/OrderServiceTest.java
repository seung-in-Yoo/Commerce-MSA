package com.commerce.order.service;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.dto.OrderItemResponse;
import com.commerce.order.dto.OrderResponse;
import com.commerce.order.exception.OrderErrorCase;
import com.commerce.order.fixture.OrderRequestFixture;
import com.commerce.order.global.exception.ApplicationException;
import com.commerce.order.messaging.OrderEventPublisher;
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
import static org.assertj.core.api.Assertions.assertThatCode;
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
    private OrderEventPublisher orderEventPublisher;

    @Nested
    @DisplayName("createOrder")
    class CreateOrder {

        @Test
        @DisplayName("성공 - 요청(productId+quantity)만으로 주문 생성, 이름 null·total 0, 이벤트 발행")
        void success() {
            given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));

            OrderResponse response = orderService.createOrder(OrderRequestFixture.defaultCreateRequest());

            assertThat(response.getCustomerId()).isEqualTo(1L);
            assertThat(response.getStatus()).isEqualTo(OrderStatus.CREATED);
            assertThat(response.getItems()).hasSize(2)
                    .extracting(OrderItemResponse::getProductId).containsExactly(1L, 3L);
            // step3b: 이름/가격은 product 소유라 아직 모름 → null, total 0
            assertThat(response.getItems()).extracting(OrderItemResponse::getProductName).containsOnlyNulls();
            assertThat(response.getTotalAmount()).isZero();
            then(orderRepository).should().save(any(Order.class));
            then(orderEventPublisher).should().publishOrderCreated(any());   // 재고 차감을 위임하는 이벤트 발행
        }

        @Test
        @DisplayName("성공 - product 호출이 사라져 product 상태와 무관하게 주문이 저장된다(디커플링)")
        void decoupledFromProduct() {
            given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));

            assertThatCode(() -> orderService.createOrder(OrderRequestFixture.defaultCreateRequest()))
                    .doesNotThrowAnyException();
            then(orderEventPublisher).should().publishOrderCreated(any());
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