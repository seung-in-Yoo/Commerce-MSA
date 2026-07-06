package com.commerce.product.integration;

import com.commerce.product.domain.Product;
import com.commerce.product.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
@DisplayName("Product 재고 차감 동시성 통합 테스트")
class ProductStockConcurrencyIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    @DisplayName("성공 - 재고 50에 100개 동시 차감 요청 → 정확히 50건만 성공, 최종 재고 0 (오버셀 없음)")
    void concurrent_decrease_never_oversells() throws InterruptedException {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long productId = tx.execute(status ->
                productRepository.save(Product.create("키보드", 30_000L, 50)).getId());

        int threadCount = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        // 100개 스레드가 동시에 각자 1개씩 차감 시도, 각 호출은 독립 트랜잭션
        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(); // 신호가 올 때까지 대기
                    int updated = tx.execute(status -> productRepository.decreaseStock(productId, 1));
                    if (updated == 1) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        pool.shutdown();

        // WHERE stockQuantity >= :quantity 가드로 정확히 50건만 성공하고 재고는 0에서 멈춤
        int finalStock = tx.execute(status ->
                productRepository.findById(productId).orElseThrow().getStockQuantity());

        assertThat(successCount.get()).isEqualTo(50);
        assertThat(failureCount.get()).isEqualTo(50);
        assertThat(finalStock).isZero();
    }
}