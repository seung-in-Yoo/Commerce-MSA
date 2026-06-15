package com.commerce.order.messaging;

import com.commerce.order.messaging.event.PaymentProcessedEvent;
import com.commerce.order.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PaymentResultListener 단위 테스트")
class PaymentResultListenerTest {

    @InjectMocks
    private PaymentResultListener paymentResultListener;

    @Mock
    private OrderService orderService;

    @Nested
    @DisplayName("onPaymentProcessed")
    class OnPaymentProcessed {

        @Test
        @DisplayName("성공 - FAILED면 해당 주문을 취소(cancelOrder 호출)")
        void failed_cancelsOrder() {
            PaymentProcessedEvent event = new PaymentProcessedEvent(
                    7L, 11L, PaymentProcessedEvent.Result.FAILED, 2_000_000L, "PAYMENT_LIMIT_EXCEEDED");

            paymentResultListener.onPaymentProcessed(event);

            then(orderService).should().cancelOrder(7L);
        }

        @Test
        @DisplayName("성공 - APPROVED면 order는 아무 것도 하지 않음(취소 호출 없음)")
        void approved_doesNothing() {
            PaymentProcessedEvent event = new PaymentProcessedEvent(
                    7L, 10L, PaymentProcessedEvent.Result.APPROVED, 660000L, null);

            paymentResultListener.onPaymentProcessed(event);

            then(orderService).should(never()).cancelOrder(7L);
        }
    }
}