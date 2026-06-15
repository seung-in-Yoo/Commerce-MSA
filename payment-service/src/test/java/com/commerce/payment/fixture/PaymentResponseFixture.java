package com.commerce.payment.fixture;

import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.dto.PaymentResponse;

import java.time.LocalDateTime;

public class PaymentResponseFixture {

    public static PaymentResponse approved(Long orderId, long amount) {
        return PaymentResponse.builder()
                .paymentId(10L)
                .orderId(orderId)
                .amount(amount)
                .status(PaymentStatus.APPROVED)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
    }

    public static PaymentResponse failed(Long orderId, long amount) {
        return PaymentResponse.builder()
                .paymentId(11L)
                .orderId(orderId)
                .amount(amount)
                .status(PaymentStatus.FAILED)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
    }
}