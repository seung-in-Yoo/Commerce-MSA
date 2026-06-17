package com.commerce.payment.messaging;

import com.commerce.payment.messaging.command.RefundPaymentCommand;
import com.commerce.payment.messaging.inbox.ProcessedMessage;
import com.commerce.payment.messaging.inbox.ProcessedMessageRepository;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RefundCommandListener 단위 테스트")
class RefundCommandListenerTest {

    @InjectMocks
    private RefundCommandListener refundCommandListener;

    @Mock
    private PaymentService paymentService;

    @Mock
    private ProcessedMessageRepository processedMessageRepository;

    @Nested
    @DisplayName("onRefundPayment")
    class OnRefundPayment {

        @Test
        @DisplayName("성공 - 환불 명령 수신 시 해당 주문의 결제를 환불 + inbox 기록")
        void refundsPayment() {
            given(processedMessageRepository.existsById("refund-msg-1")).willReturn(false);

            refundCommandListener.onRefundPayment(new RefundPaymentCommand("refund-msg-1", 1L));

            then(paymentService).should().refund(1L);
            then(processedMessageRepository).should().save(any(ProcessedMessage.class));
        }

        @Test
        @DisplayName("멱등 - 이미 처리한 messageId 재배달 → 환불/기록 모두 스킵")
        void duplicate_skipped() {
            given(processedMessageRepository.existsById("refund-msg-1")).willReturn(true);

            refundCommandListener.onRefundPayment(new RefundPaymentCommand("refund-msg-1", 1L));

            then(paymentService).should(never()).refund(anyLong());
            then(processedMessageRepository).should(never()).save(any(ProcessedMessage.class));
        }
    }
}