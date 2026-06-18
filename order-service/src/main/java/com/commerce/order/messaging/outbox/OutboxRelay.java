package com.commerce.order.messaging.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

// outbox 릴레이 -> PENDING 행을 주기적으로 읽어 실제 Kafka로 발행하고 SENT로 마킹
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxMessageRepository outboxRepository;
    private final KafkaTemplate<String, String> outboxKafkaTemplate;
    // 분산추적: 적재 시 저장해둔 trace context를 복원해 원래 trace로 발행
    private final Tracer tracer;
    private final Propagator propagator;
    private final ObjectMapper objectMapper;

    // 한 번의 폴링 = 한 트랜잭션. PENDING을 오래된 순서로 한 묶음 발행한다.
    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxMessage> pending = outboxRepository.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        if (pending.isEmpty()) {
            return;
        }

        for (OutboxMessage message : pending) {
            send(message);
            // 발행 성공 후 마킹 -> dirty checking으로 트랜잭션 커밋 시 SENT로 UPDATE
            message.markSent();
        }
        log.info("[order][outbox-relay] PENDING {}건 발행 완료", pending.size());
    }

    // 적재 시 저장한 trace context를 복원한 scope 안에서 발행
    private void send(OutboxMessage message) {
        Span restored = restoreSpan(message);
        if (restored == null) {
            doSend(message);
            return;
        }
        try (Tracer.SpanInScope ignored = tracer.withSpan(restored)) {
            doSend(message);
        } finally {
            restored.end();
        }
    }

    // 동기 발행(.get())으로 브로커 도착을 확인한 뒤에야 markSent 한다.
    // 실패하면 예외 -> 트랜잭션 롤백 -> 해당 행은 PENDING으로 남아 다음 폴링에서 재시도된다.
    private void doSend(OutboxMessage message) {
        try {
            outboxKafkaTemplate.send(message.getTopic(), message.getMessageKey(), message.getPayload()).get();
            log.info("[order][outbox-relay] 발행 -> topic={}, key={}, id={}",
                    message.getTopic(), message.getMessageKey(), message.getId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("outbox 발행 중단: id=" + message.getId(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("outbox 발행 실패: id=" + message.getId(), e);
        }
    }

    private Span restoreSpan(OutboxMessage message) {
        String traceContext = message.getTraceContext();
        if (traceContext == null || traceContext.isBlank()) {
            return null;
        }
        try {
            Map<String, String> carrier = objectMapper.readValue(traceContext, new TypeReference<>() {});
            return propagator.extract(carrier, (c, key) -> c.get(key))
                    .name("outbox-relay.publish")
                    .start();
        } catch (Exception e) {
            log.warn("[order][outbox-relay] trace context 복원 실패 id={} -> 추적 없이 발행", message.getId(), e);
            return null;
        }
    }
}