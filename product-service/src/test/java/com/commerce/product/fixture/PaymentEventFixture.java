package com.commerce.product.fixture;

import com.commerce.product.messaging.event.PaymentProcessedEvent;

import java.util.List;

public class PaymentEventFixture {

    // 승인된 결제 -> 재고 차감 단계로 진행
    public static PaymentProcessedEvent approvedEvent() {
        return new PaymentProcessedEvent(1L, 10L, PaymentProcessedEvent.Result.APPROVED, 660000L,
                List.of(new PaymentProcessedEvent.Item(1L, 2),
                        new PaymentProcessedEvent.Item(3L, 1)),
                null);
    }

    // 거절된 결제 -> product는 무시
    public static PaymentProcessedEvent failedEvent() {
        return new PaymentProcessedEvent(2L, 11L, PaymentProcessedEvent.Result.FAILED, 2_000_000L,
                List.of(), "PAYMENT_LIMIT_EXCEEDED");
    }
}