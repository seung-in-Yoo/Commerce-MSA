package com.commerce.payment.messaging;

import com.commerce.payment.fixture.PaymentResponseFixture;
import com.commerce.payment.messaging.command.ProcessPaymentCommand;
import com.commerce.payment.messaging.reply.PaymentProcessedReply;
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
@DisplayName("PaymentCommandListener 단위 테스트")
class PaymentCommandListenerTest {

    @InjectMocks
    private PaymentCommandListener paymentCommandListener;

    @Mock
    private PaymentService paymentService;

    @Mock
    private PaymentReplyPublisher paymentReplyPublisher;

    @Nested
    @DisplayName("onProcessPayment")
    class OnProcessPayment {

        @Test
        @DisplayName("성공 - 승인되면 PaymentProcessed(APPROVED) 응답 발행")
        void approved_publishesApprovedReply() {
            given(paymentService.pay(eq(1L), anyLong()))
                    .willReturn(PaymentResponseFixture.approved(1L, 660000L));

            paymentCommandListener.onProcessPayment(new ProcessPaymentCommand(1L, 660000L));

            then(paymentService).should().pay(1L, 660000L);

            ArgumentCaptor<PaymentProcessedReply> captor = ArgumentCaptor.forClass(PaymentProcessedReply.class);
            then(paymentReplyPublisher).should().publishPaymentProcessed(captor.capture());
            PaymentProcessedReply reply = captor.getValue();
            assertThat(reply.orderId()).isEqualTo(1L);
            assertThat(reply.paymentId()).isEqualTo(10L);
            assertThat(reply.result()).isEqualTo(PaymentProcessedReply.Result.APPROVED);
            assertThat(reply.reasonCode()).isNull();
        }

        @Test
        @DisplayName("실패 - 거절되면 PaymentProcessed(FAILED, 사유 코드) 응답 발행")
        void failed_publishesFailedReply() {
            given(paymentService.pay(eq(2L), anyLong()))
                    .willReturn(PaymentResponseFixture.failed(2L, 2_000_000L));

            paymentCommandListener.onProcessPayment(new ProcessPaymentCommand(2L, 2_000_000L));

            ArgumentCaptor<PaymentProcessedReply> captor = ArgumentCaptor.forClass(PaymentProcessedReply.class);
            then(paymentReplyPublisher).should().publishPaymentProcessed(captor.capture());
            PaymentProcessedReply reply = captor.getValue();
            assertThat(reply.result()).isEqualTo(PaymentProcessedReply.Result.FAILED);
            assertThat(reply.reasonCode()).isEqualTo("PAYMENT_LIMIT_EXCEEDED");
        }
    }
}