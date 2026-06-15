package com.commerce.payment.service;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.dto.PaymentResponse;
import com.commerce.payment.exception.PaymentErrorCase;
import com.commerce.payment.global.exception.ApplicationException;
import com.commerce.payment.repository.PaymentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final long approvalLimit;

    public PaymentService(PaymentRepository paymentRepository,
                          @Value("${payment.approval-limit:1000000}") long approvalLimit) {
        this.paymentRepository = paymentRepository;
        this.approvalLimit = approvalLimit;
    }

    // 결제 승인 시도 -> 금액이 한도 이하면 APPROVED, 초과면 FAILED로 기록
    @Transactional
    public PaymentResponse pay(Long orderId, long amount) {
        Payment payment = amount <= approvalLimit
                ? Payment.approved(orderId, amount)
                : Payment.failed(orderId, amount);
        return PaymentResponse.from(paymentRepository.save(payment));
    }

    // 재고 실패 수신 시 해당 주문의 결제를 환불
    @Transactional
    public PaymentResponse refund(Long orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> ApplicationException.from(PaymentErrorCase.PAYMENT_NOT_FOUND));
        payment.refund();
        return PaymentResponse.from(payment);
    }

    public PaymentResponse getPayment(Long paymentId) {
        return PaymentResponse.from(findPayment(paymentId));
    }

    public List<PaymentResponse> getAllPayments() {
        return paymentRepository.findAll().stream()
                .map(PaymentResponse::from)
                .toList();
    }

    private Payment findPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> ApplicationException.from(PaymentErrorCase.PAYMENT_NOT_FOUND));
    }
}