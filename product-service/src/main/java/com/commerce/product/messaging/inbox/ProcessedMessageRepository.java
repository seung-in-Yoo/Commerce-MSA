package com.commerce.product.messaging.inbox;

import org.springframework.data.jpa.repository.JpaRepository;

// inbox 조회/기록 -> 중복 판정은 PK(messageId) 존재 여부로
public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, String> {
}