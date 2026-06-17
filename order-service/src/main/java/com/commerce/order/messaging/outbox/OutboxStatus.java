package com.commerce.order.messaging.outbox;

// outbox 메시지 발행 상태
public enum OutboxStatus {
    PENDING, // 비즈니스 트랜잭션과 함께 기록만 됨 (아직 Kafka로 안 나감)
    SENT // 릴레이가 실제로 Kafka에 발행 완료
}