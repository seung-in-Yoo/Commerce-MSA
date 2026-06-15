package com.commerce.order.service;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.domain.ProductSnapshot;
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
        @DisplayName("성공 - 예상 단가로 total 산정·PENDING 저장, 이름은 null, 이벤트 발행")
        void success() {
            given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));

            OrderResponse response = orderService.createOrder(OrderRequestFixture.defaultCreateRequest());

            assertThat(response.getCustomerId()).isEqualTo(1L);
            assertThat(response.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(response.getItems()).hasSize(2)
                    .extracting(OrderItemResponse::getProductId).containsExactly(1L, 3L);
            assertThat(response.getTotalAmount()).isEqualTo(660000L);
            assertThat(response.getItems()).extracting(OrderItemResponse::getProductName).containsOnlyNulls();
            then(orderRepository).should().save(any(Order.class));
            then(orderEventPublisher).should().publishOrderCreated(any());   // Saga 시작 이벤트 발행
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
            given(order.getStatus()).willReturn(OrderStatus.PENDING);
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

    @Nested
    @DisplayName("confirmOrder")
    class ConfirmOrder {

        @Test
        @DisplayName("성공 - 주문을 찾아 스냅샷 적용 후 CONFIRMED, total 재계산")
        void success() {
            Order order = Order.create(1L);
            order.addItem(100L, 2, 25000L);   // 예상 단가 25000 → 확정 시 실제 단가 30000으로 덮어쓰여 재계산
            given(orderRepository.findById(1L)).willReturn(Optional.of(order));

            orderService.confirmOrder(1L, List.of(new ProductSnapshot(100L, "키보드", 30000L)));

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getTotalAmount()).isEqualTo(60000L);
        }

        @Test
        @DisplayName("실패 - 존재하지 않는 주문 → ORDER_NOT_FOUND")
        void notFound() {
            given(orderRepository.findById(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.confirmOrder(99L, List.of()))
                    .isInstanceOf(ApplicationException.class)
                    .extracting(e -> ((ApplicationException) e).getErrorCase())
                    .isEqualTo(OrderErrorCase.ORDER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("cancelOrder")
    class CancelOrder {

        @Test
        @DisplayName("성공 - 주문을 찾아 CANCELLED로 전이")
        void success() {
            Order order = Order.create(1L);
            order.addItem(100L, 2, 30000L);
            given(orderRepository.findById(1L)).willReturn(Optional.of(order));

            orderService.cancelOrder(1L);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }
    }
}