package com.commerce.order.messaging;

import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.messaging.command.DeductStockCommand;
import com.commerce.order.messaging.command.ProcessPaymentCommand;
import com.commerce.order.messaging.command.RefundPaymentCommand;
import com.commerce.order.messaging.inbox.ProcessedMessage;
import com.commerce.order.messaging.inbox.ProcessedMessageRepository;
import com.commerce.order.messaging.reply.PaymentProcessedReply;
import com.commerce.order.messaging.reply.StockProcessedReply;
import com.commerce.order.repository.OrderRepository;

import java.util.List;
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

    @Mock
    private ProcessedMessageRepository processedMessageRepository;

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
        @DisplayName("성공 - 결제 승인 응답 → 주문 항목으로 DeductStock 명령 발행 + inbox 기록")
        void approved_sendsDeductStock() {
            given(processedMessageRepository.existsById("pay-msg-1")).willReturn(false);
            Order order = Order.create(1L);
            order.addItem(100L, 2, 30000L);
            ReflectionTestUtils.setField(order, "id", 1L);
            given(orderRepository.findById(1L)).willReturn(Optional.of(order));

            orchestrator.onPaymentReply(
                    new PaymentProcessedReply("pay-msg-1", 1L, 10L, PaymentProcessedReply.Result.APPROVED, null));

            ArgumentCaptor<DeductStockCommand> captor = ArgumentCaptor.forClass(DeductStockCommand.class);
            then(commandPublisher).should().sendDeductStock(captor.capture());
            DeductStockCommand command = captor.getValue();
            assertThat(command.orderId()).isEqualTo(1L);
            assertThat(command.items()).hasSize(1)
                    .extracting(DeductStockCommand.Item::productId).containsExactly(100L);
            assertThat(command.items()).extracting(DeductStockCommand.Item::quantity).containsExactly(2);
            then(processedMessageRepository).should().save(any(ProcessedMessage.class));
        }

        @Test
        @DisplayName("성공 - 결제 거절 응답 → 주문 취소(CANCELLED), 재고 명령 없음")
        void failed_cancelsOrder() {
            given(processedMessageRepository.existsById("pay-msg-2")).willReturn(false);
            Order order = Order.create(1L);
            order.addItem(100L, 2, 30000L);
            given(orderRepository.findById(2L)).willReturn(Optional.of(order));

            orchestrator.onPaymentReply(
                    new PaymentProcessedReply("pay-msg-2", 2L, 11L, PaymentProcessedReply.Result.FAILED,
                            "PAYMENT_LIMIT_EXCEEDED"));

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            then(commandPublisher).should(never()).sendDeductStock(any());
            then(processedMessageRepository).should().save(any(ProcessedMessage.class));
        }

        @Test
        @DisplayName("멱등 - 이미 처리한 응답 재배달 → 주문 조회/명령 발행/기록 모두 스킵")
        void duplicate_skipped() {
            given(processedMessageRepository.existsById("pay-msg-1")).willReturn(true);

            orchestrator.onPaymentReply(
                    new PaymentProcessedReply("pay-msg-1", 1L, 10L, PaymentProcessedReply.Result.APPROVED, null));

            then(orderRepository).should(never()).findById(any());
            then(commandPublisher).should(never()).sendDeductStock(any());
            then(processedMessageRepository).should(never()).save(any(ProcessedMessage.class));
        }
    }

    @Nested
    @DisplayName("onStockReply")
    class OnStockReply {

        @Test
        @DisplayName("성공 - 재고 차감 성공 응답 → 스냅샷 적용 후 주문 확정(CONFIRMED), total 재계산 + inbox 기록")
        void deducted_confirmsOrder() {
            given(processedMessageRepository.existsById("stock-msg-1")).willReturn(false);
            Order order = Order.create(1L);
            order.addItem(100L, 2, 25000L);
            given(orderRepository.findById(1L)).willReturn(Optional.of(order));

            orchestrator.onStockReply(new StockProcessedReply("stock-msg-1", 1L, StockProcessedReply.Result.DEDUCTED,
                    List.of(new StockProcessedReply.Item(100L, "키보드", 30000L)), null));

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getTotalAmount()).isEqualTo(60000L);
            then(processedMessageRepository).should().save(any(ProcessedMessage.class));
        }

        @Test
        @DisplayName("성공 - 재고 차감 실패 응답 → 환불 명령 발행 + 주문 취소(CANCELLED)")
        void failed_refundsAndCancels() {
            given(processedMessageRepository.existsById("stock-msg-2")).willReturn(false);
            Order order = Order.create(1L);
            order.addItem(100L, 2, 30000L);
            ReflectionTestUtils.setField(order, "id", 2L);
            given(orderRepository.findById(2L)).willReturn(Optional.of(order));

            orchestrator.onStockReply(new StockProcessedReply("stock-msg-2", 2L, StockProcessedReply.Result.FAILED,
                    List.of(), "OUT_OF_STOCK"));

            ArgumentCaptor<RefundPaymentCommand> captor = ArgumentCaptor.forClass(RefundPaymentCommand.class);
            then(commandPublisher).should().sendRefundPayment(captor.capture());
            assertThat(captor.getValue().orderId()).isEqualTo(2L);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            then(processedMessageRepository).should().save(any(ProcessedMessage.class));
        }

        @Test
        @DisplayName("멱등 - 이미 처리한 응답 재배달 → 환불 명령/주문 변경/기록 모두 스킵")
        void duplicate_skipped() {
            given(processedMessageRepository.existsById("stock-msg-2")).willReturn(true);

            orchestrator.onStockReply(new StockProcessedReply("stock-msg-2", 2L, StockProcessedReply.Result.FAILED,
                    List.of(), "OUT_OF_STOCK"));

            then(orderRepository).should(never()).findById(any());
            then(commandPublisher).should(never()).sendRefundPayment(any());
            then(processedMessageRepository).should(never()).save(any(ProcessedMessage.class));
        }
    }
}