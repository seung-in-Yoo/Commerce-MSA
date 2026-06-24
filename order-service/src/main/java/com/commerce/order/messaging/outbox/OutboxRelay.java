package com.commerce.order.messaging.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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

    // 한 번의 폴링 = 한 트랜잭션 PENDING을 오래된 순서로 한 묶음 발행한다
    // producer 배치화: 예전엔 "한 건 send → 즉시 .get()으로 ack 대기"를 반복해서
    // 예전: 한 건 send → 즉시 .get()으로 ack 대기
    // 현재: 전부 비동기 send만 걸고, flush()로 한 번에 밀어낸 뒤, 도착을 확인
    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxMessage> pending = outboxRepository.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        if (pending.isEmpty()) {
            return;
        }

        List<SendHandle> handles = new ArrayList<>(pending.size());
        try {
            // (1) 전부 비동기 발행만 (.get() 없음) -> producer가 batch.size/linger.ms로 묶을 여지가 생긴다
            for (OutboxMessage message : pending) {
                handles.add(sendAsync(message));
            }
            // (2) 버퍼에 쌓인 레코드를 한 번에 브로커로 밀어낸다(반환 시 모든 send 완료)
            outboxKafkaTemplate.flush();
            // (3) 도착 확인 후 마킹 -> 실패하면 예외 -> 트랜잭션 롤백 -> PENDING 유지(다음 폴링 재시도)
            for (SendHandle handle : handles) {
                confirm(handle);
            }
            log.info("[order][outbox-relay] PENDING {}건 발행 완료", pending.size());
        } finally {
            // 복원한 trace span은 성공/실패와 무관하게 모두 닫는다(누수 방지)
            handles.forEach(SendHandle::endSpan);
        }
    }

    // 적재 시 저장한 trace context를 복원한 scope 안에서 "비동기 send만" 건다.
    // 도착 확인(.get())은 flush 뒤 confirm()에서 한 번에 한다.
    private SendHandle sendAsync(OutboxMessage message) {
        Span restored = restoreSpan(message);
        if (restored == null) {
            return new SendHandle(message, doSendAsync(message), null);
        }
        try (Tracer.SpanInScope ignored = tracer.withSpan(restored)) {
            return new SendHandle(message, doSendAsync(message), restored);
        }
    }

    private CompletableFuture<SendResult<String, String>> doSendAsync(OutboxMessage message) {
        return outboxKafkaTemplate.send(message.getTopic(), message.getMessageKey(), message.getPayload());
    }

    // flush 이후라 future는 이미 완료 상태. 도착을 확인하고 SENT로 마킹한다.
    // 실패하면 예외 -> 트랜잭션 롤백 -> 해당 행은 PENDING으로 남아 다음 폴링에서 재시도된다.
    private void confirm(SendHandle handle) {
        OutboxMessage message = handle.message();
        try {
            handle.future().get();
            // dirty checking으로 트랜잭션 커밋 시 SENT로 UPDATE
            message.markSent();
            log.info("[order][outbox-relay] 발행 -> topic={}, key={}, id={}",
                    message.getTopic(), message.getMessageKey(), message.getId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("outbox 발행 중단: id=" + message.getId(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("outbox 발행 실패: id=" + message.getId(), e);
        }
    }

    // 한 건의 비동기 발행 결과(메시지 + future + 복원한 span). span은 없을 수 있다(null).
    private record SendHandle(OutboxMessage message,
                              CompletableFuture<SendResult<String, String>> future,
                              Span span) {
        void endSpan() {
            if (span != null) {
                span.end();
            }
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