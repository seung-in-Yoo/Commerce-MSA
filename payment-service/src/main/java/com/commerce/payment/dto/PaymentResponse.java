package com.commerce.payment.dto;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class PaymentResponse {

    private final Long paymentId;
    private final Long orderId;
    private final long amount;
    private final PaymentStatus status;
    private final LocalDateTime createdAt;

    public static PaymentResponse from(Payment payment) {
        return PaymentResponse.builder()
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .amount(payment.getAmount())
                .status(payment.getStatus())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}