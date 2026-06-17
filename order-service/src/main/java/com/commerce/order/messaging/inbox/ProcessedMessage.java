package com.commerce.order.messaging.inbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 멱등 컨슈머용 inbox 레코드
// 같은 응답이 재배달되면 해당 PK 존재 여부로 걸러 한 번만 처리
@Entity
@Table(name = "processed_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedMessage {

    @Id
    @Column(length = 36)
    private String messageId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime processedAt;

    private ProcessedMessage(String messageId) {
        this.messageId = messageId;
        this.processedAt = LocalDateTime.now();
    }

    public static ProcessedMessage of(String messageId) {
        return new ProcessedMessage(messageId);
    }
}