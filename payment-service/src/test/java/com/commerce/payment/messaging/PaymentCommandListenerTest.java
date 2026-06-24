package com.commerce.payment.messaging;

import com.commerce.payment.fixture.PaymentResponseFixture;
import com.commerce.payment.messaging.command.ProcessPaymentCommand;
import com.commerce.payment.messaging.inbox.ProcessedMessage;
import com.commerce.payment.messaging.inbox.ProcessedMessageRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

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

    @Mock
    private ProcessedMessageRepository processedMessageRepository;

    @Mock
    private ProcessingDelay processingDelay;

    @Nested
    @DisplayName("onProcessPayment")
    class OnProcessPayment {

        @Test
        @DisplayName("성공 - 승인되면 PaymentProcessed(APPROVED) 응답 발행 + inbox 기록")
        void approved_publishesApprovedReply() {
            given(processedMessageRepository.existsById("msg-1")).willReturn(false);
            given(paymentService.pay(eq(1L), anyLong()))
                    .willReturn(PaymentResponseFixture.approved(1L, 660000L));

            paymentCommandListener.onProcessPayment(new ProcessPaymentCommand("msg-1", 1L, 660000L));

            then(paymentService).should().pay(1L, 660000L);
            then(processedMessageRepository).should().save(any(ProcessedMessage.class));

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
            given(processedMessageRepository.existsById("msg-2")).willReturn(false);
            given(paymentService.pay(eq(2L), anyLong()))
                    .willReturn(PaymentResponseFixture.failed(2L, 2_000_000L));

            paymentCommandListener.onProcessPayment(new ProcessPaymentCommand("msg-2", 2L, 2_000_000L));

            ArgumentCaptor<PaymentProcessedReply> captor = ArgumentCaptor.forClass(PaymentProcessedReply.class);
            then(paymentReplyPublisher).should().publishPaymentProcessed(captor.capture());
            PaymentProcessedReply reply = captor.getValue();
            assertThat(reply.result()).isEqualTo(PaymentProcessedReply.Result.FAILED);
            assertThat(reply.reasonCode()).isEqualTo("PAYMENT_LIMIT_EXCEEDED");
        }

        @Test
        @DisplayName("멱등 - 이미 처리한 messageId 재배달 → 결제/응답/기록 모두 스킵")
        void duplicate_skipped() {
            given(processedMessageRepository.existsById("msg-1")).willReturn(true);

            paymentCommandListener.onProcessPayment(new ProcessPaymentCommand("msg-1", 1L, 660000L));

            then(paymentService).should(never()).pay(anyLong(), anyLong());
            then(processedMessageRepository).should(never()).save(any(ProcessedMessage.class));
            then(paymentReplyPublisher).should(never()).publishPaymentProcessed(any());
        }
    }
}