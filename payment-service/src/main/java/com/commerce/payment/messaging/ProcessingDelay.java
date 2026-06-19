package com.commerce.payment.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ProcessingDelay {

    private final long delayMs;

    public ProcessingDelay(@Value("${payment.processing-delay-ms:0}") long delayMs) {
        this.delayMs = delayMs;
        if (delayMs > 0) {
            log.info("[payment] 처리 지연 시뮬레이션 활성화 -> {}ms/건", delayMs);
        }
    }

    // 결제 처리 1건당 호출
    public void apply() {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}