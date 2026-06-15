package com.commerce.payment.messaging;

import com.commerce.payment.fixture.OrderEventFixture;
import com.commerce.payment.fixture.PaymentResponseFixture;
import com.commerce.payment.messaging.event.PaymentProcessedEvent;
import com.commerce.payment.service.PaymentService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderEventListener 단위 테스트")
class OrderEventListenerTest {

    @InjectMocks
    private OrderEventListener orderEventListener;

    @Mock
    private PaymentService paymentService;

    @Mock
    private PaymentEventPublisher paymentEventPublisher;

    @Nested
    @DisplayName("onOrderCreated")
    class OnOrderCreated {

        @Test
        @DisplayName("성공 - 승인되면 PaymentProcessed(APPROVED, items 전달) 발행")
        void approved_publishesApprovedWithItems() {
            given(paymentService.pay(eq(1L), anyLong()))
                    .willReturn(PaymentResponseFixture.approved(1L, 660000L));

            orderEventListener.onOrderCreated(OrderEventFixture.defaultEvent());

            then(paymentService).should().pay(1L, 660000L);

            ArgumentCaptor<PaymentProcessedEvent> captor = ArgumentCaptor.forClass(PaymentProcessedEvent.class);
            then(paymentEventPublisher).should().publishPaymentProcessed(captor.capture());
            PaymentProcessedEvent published = captor.getValue();
            assertThat(published.orderId()).isEqualTo(1L);
            assertThat(published.paymentId()).isEqualTo(10L);
            assertThat(published.result()).isEqualTo(PaymentProcessedEvent.Result.APPROVED);
            assertThat(published.reasonCode()).isNull();
            assertThat(published.items())
                    .extracting(PaymentProcessedEvent.Item::productId).containsExactly(1L, 3L);
        }

        @Test
        @DisplayName("실패 - 거절되면 PaymentProcessed(FAILED, items 비움) 발행")
        void failed_publishesFailedWithoutItems() {
            given(paymentService.pay(eq(2L), anyLong()))
                    .willReturn(PaymentResponseFixture.failed(2L, 2_000_000L));

            orderEventListener.onOrderCreated(
                    OrderEventFixture.event(2L, 2_000_000L, OrderEventFixture.defaultEvent().items()));

            ArgumentCaptor<PaymentProcessedEvent> captor = ArgumentCaptor.forClass(PaymentProcessedEvent.class);
            then(paymentEventPublisher).should().publishPaymentProcessed(captor.capture());
            PaymentProcessedEvent published = captor.getValue();
            assertThat(published.result()).isEqualTo(PaymentProcessedEvent.Result.FAILED);
            assertThat(published.reasonCode()).isEqualTo("PAYMENT_LIMIT_EXCEEDED");
            assertThat(published.items()).isEmpty();
        }
    }
}