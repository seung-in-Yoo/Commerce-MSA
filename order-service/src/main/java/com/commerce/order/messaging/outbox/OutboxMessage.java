package com.commerce.order.messaging.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

// Outbox 패턴의 발행 대기함(아웃박스) 레코드
// 실제 Kafka 발행은 별도 릴레이가 PENDING 행을 읽어 처리하고 SENT로 마킹
@Entity
@Table(name = "outbox_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxMessage {

    @Id
    @Column(length = 36)
    private String id;

    // 발행 대상 토픽
    @Column(nullable = false)
    private String topic;

    // Kafka 메시지 키
    @Column(nullable = false)
    private String messageKey;

    // 직렬화된 메시지 본문
    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OutboxStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 발행 완료 시각(PENDING 동안은 null)
    private LocalDateTime sentAt;

    // 분산추적: 적재 시점의 trace context
    @Lob
    @Column(columnDefinition = "TEXT")
    private String traceContext;

    private OutboxMessage(String topic, String messageKey, String payload, String traceContext) {
        this.id = UUID.randomUUID().toString();
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.createdAt = LocalDateTime.now();
        this.traceContext = traceContext;
    }

    public static OutboxMessage create(String topic, String messageKey, String payload, String traceContext) {
        return new OutboxMessage(topic, messageKey, payload, traceContext);
    }

    // 릴레이가 실제 발행에 성공하면 호출 -> 다음 폴링에서 다시 잡음
    public void markSent() {
        this.status = OutboxStatus.SENT;
        this.sentAt = LocalDateTime.now();
    }
}