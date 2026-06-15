package com.commerce.payment.messaging;

import com.commerce.payment.messaging.event.StockProcessedEvent;
import com.commerce.payment.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StockResultListener 단위 테스트")
class StockResultListenerTest {

    @InjectMocks
    private StockResultListener stockResultListener;

    @Mock
    private PaymentService paymentService;

    @Nested
    @DisplayName("onStockProcessed")
    class OnStockProcessed {

        @Test
        @DisplayName("성공 - FAILED면 해당 주문의 결제를 환불(refund 호출)")
        void failed_refunds() {
            StockProcessedEvent event = new StockProcessedEvent(
                    5L, StockProcessedEvent.Result.FAILED, "PRODUCT_002");

            stockResultListener.onStockProcessed(event);

            then(paymentService).should().refund(5L);
        }

        @Test
        @DisplayName("성공 - DEDUCTED면 결제를 유지하고 아무 것도 하지 않음(환불 호출 없음)")
        void deducted_doesNothing() {
            StockProcessedEvent event = new StockProcessedEvent(
                    5L, StockProcessedEvent.Result.DEDUCTED, null);

            stockResultListener.onStockProcessed(event);

            then(paymentService).should(never()).refund(anyLong());
        }
    }
}