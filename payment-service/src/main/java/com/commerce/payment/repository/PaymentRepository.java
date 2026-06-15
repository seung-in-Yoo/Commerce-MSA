package com.commerce.payment.repository;

import com.commerce.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // 환불(보상)은 orderId로 해당 주문의 결제를 찾아 처리
    Optional<Payment> findByOrderId(Long orderId);
}