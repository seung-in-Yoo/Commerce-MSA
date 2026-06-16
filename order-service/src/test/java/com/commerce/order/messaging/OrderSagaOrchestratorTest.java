package com.commerce.order.messaging;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.messaging.command.DeductStockCommand;
import com.commerce.order.messaging.command.ProcessPaymentCommand;
import com.commerce.order.messaging.reply.PaymentProcessedReply;
import com.commerce.order.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderSagaOrchestrator 단위 테스트")
class OrderSagaOrchestratorTest {

    @InjectMocks
    private OrderSagaOrchestrator orchestrator;

    @Mock
    private SagaCommandPublisher commandPublisher;

    @Mock
    private OrderRepository orderRepository;

    @Nested
    @DisplayName("start")
    class Start {

        @Test
        @DisplayName("성공 - 주문 금액으로 ProcessPayment 명령 발행")
        void success() {
            Order order = mock(Order.class);
            given(order.getId()).willReturn(1L);
            given(order.getTotalAmount()).willReturn(60000L);

            orchestrator.start(order);

            ArgumentCaptor<ProcessPaymentCommand> captor = ArgumentCaptor.forClass(ProcessPaymentCommand.class);
            then(commandPublisher).should().sendProcessPayment(captor.capture());
            ProcessPaymentCommand command = captor.getValue();
            assertThat(command.orderId()).isEqualTo(1L);
            assertThat(command.amount()).isEqualTo(60000L);
        }
    }

    @Nested
    @DisplayName("onPaymentReply")
    class OnPaymentReply {

        @Test
        @DisplayName("성공 - 결제 승인 응답 → 주문 항목으로 DeductStock 명령 발행")
        void approved_sendsDeductStock() {
            Order order = Order.create(1L);
            order.addItem(100L, 2, 30000L);
            ReflectionTestUtils.setField(order, "id", 1L);
            given(orderRepository.findById(1L)).willReturn(Optional.of(order));

            orchestrator.onPaymentReply(
                    new PaymentProcessedReply(1L, 10L, PaymentProcessedReply.Result.APPROVED, null));

            ArgumentCaptor<DeductStockCommand> captor = ArgumentCaptor.forClass(DeductStockCommand.class);
            then(commandPublisher).should().sendDeductStock(captor.capture());
            DeductStockCommand command = captor.getValue();
            assertThat(command.orderId()).isEqualTo(1L);
            assertThat(command.items()).hasSize(1)
                    .extracting(DeductStockCommand.Item::productId).containsExactly(100L);
            assertThat(command.items()).extracting(DeductStockCommand.Item::quantity).containsExactly(2);
        }

        @Test
        @DisplayName("성공 - 결제 거절 응답 → 주문 취소(CANCELLED), 재고 명령 없음")
        void failed_cancelsOrder() {
            Order order = Order.create(1L);
            order.addItem(100L, 2, 30000L);
            given(orderRepository.findById(2L)).willReturn(Optional.of(order));

            orchestrator.onPaymentReply(
                    new PaymentProcessedReply(2L, 11L, PaymentProcessedReply.Result.FAILED, "PAYMENT_LIMIT_EXCEEDED"));

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            then(commandPublisher).should(never()).sendDeductStock(any());
        }
    }
}