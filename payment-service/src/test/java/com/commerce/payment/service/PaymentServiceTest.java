package com.commerce.payment.service;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.dto.PaymentResponse;
import com.commerce.payment.exception.PaymentErrorCase;
import com.commerce.payment.global.exception.ApplicationException;
import com.commerce.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PaymentService 단위 테스트")
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    private PaymentService paymentService;

    private static final long LIMIT = 1_000_000L;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository, LIMIT);
        given(paymentRepository.save(any(Payment.class))).willAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("pay")
    class Pay {

        @Test
        @DisplayName("성공 - 금액이 한도 이하면 APPROVED로 기록")
        void approved() {
            PaymentResponse response = paymentService.pay(1L, LIMIT);

            assertThat(response.getStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(response.getOrderId()).isEqualTo(1L);
            assertThat(response.getAmount()).isEqualTo(LIMIT);
            then(paymentRepository).should().save(any(Payment.class));
        }

        @Test
        @DisplayName("실패 - 금액이 한도를 초과하면 FAILED로 기록")
        void failed() {
            PaymentResponse response = paymentService.pay(2L, LIMIT + 1);

            assertThat(response.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(response.getOrderId()).isEqualTo(2L);
            then(paymentRepository).should().save(any(Payment.class));
        }
    }

    @Nested
    @DisplayName("refund")
    class Refund {

        @Test
        @DisplayName("성공 - 승인된 결제를 찾아 REFUNDED로 전이")
        void approved_becomesRefunded() {
            Payment approved = Payment.approved(1L, 660000L);
            given(paymentRepository.findByOrderId(1L)).willReturn(Optional.of(approved));

            PaymentResponse response = paymentService.refund(1L);

            assertThat(response.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        }

        @Test
        @DisplayName("성공 - 이미 환불된 결제를 다시 환불해도 REFUNDED 유지(멱등)")
        void alreadyRefunded_staysRefunded() {
            Payment approved = Payment.approved(1L, 660000L);
            approved.refund();   // 이미 한 번 환불
            given(paymentRepository.findByOrderId(1L)).willReturn(Optional.of(approved));

            PaymentResponse response = paymentService.refund(1L);

            assertThat(response.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        }

        @Test
        @DisplayName("실패 - 결제가 없으면 PAYMENT_NOT_FOUND")
        void notFound() {
            given(paymentRepository.findByOrderId(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.refund(99L))
                    .isInstanceOf(ApplicationException.class)
                    .extracting(e -> ((ApplicationException) e).getErrorCase())
                    .isEqualTo(PaymentErrorCase.PAYMENT_NOT_FOUND);
        }
    }
}