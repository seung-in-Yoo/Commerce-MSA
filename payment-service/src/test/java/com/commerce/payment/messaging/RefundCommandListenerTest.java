package com.commerce.payment.messaging;

import com.commerce.payment.messaging.command.RefundPaymentCommand;
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

import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RefundCommandListener 단위 테스트")
class RefundCommandListenerTest {

    @InjectMocks
    private RefundCommandListener refundCommandListener;

    @Mock
    private PaymentService paymentService;

    @Nested
    @DisplayName("onRefundPayment")
    class OnRefundPayment {

        @Test
        @DisplayName("성공 - 환불 명령 수신 시 해당 주문의 결제를 환불")
        void refundsPayment() {
            refundCommandListener.onRefundPayment(new RefundPaymentCommand(1L));

            then(paymentService).should().refund(1L);
        }
    }
}