package com.commerce.order.messaging.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// outbox 조회/기록
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, String> {

    List<OutboxMessage> findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus status);
}