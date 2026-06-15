# commerce-msa

모놀리식 → MSA 전환을 단계적으로 학습하는 프로젝트. 도메인은 커머스(주문-결제-재고).
전부 로컬 docker-compose에서 돌린다. 클라우드 배포 없음.

> "어떻게 이해해 갔나"의 단계별 기록은 [LEARNING_JOURNEY.md](./LEARNING_JOURNEY.md), 막힌 것은 [TROUBLESHOOTING.md](./TROUBLESHOOTING.md).

## 학습 로드맵
1. ✅ 서비스 1개 + DB 1개 — Spring Boot를 compose로 띄우기 (워밍업)
2. ✅ 서비스 2개 + DB 2개, REST 동기 호출 — "남의 DB JOIN 못 함", "쟤 죽으면 나도 죽음" 체감
3. ✅ 동기 호출을 Kafka 이벤트로 전환 (3a 발행/수신 추가 → 3b 동기 제거, 완전 비동기)
4. **[현재] Saga(choreography vs orchestration), Outbox, eventual consistency**
   - ✅ 4a choreography 보상 (order↔product)
   - ✅ 4b payment 추가 → 주문→결제→재고 **3-step Saga + 보상 사슬**
   - ⬜ 4c orchestration 대조 · 4d 멱등성 · 4e Outbox
5. ⬜ API Gateway, 분산 추적, Circuit Breaker(Resilience4j)

## 지키는 습관 (12-factor / k8s 대비)
- 설정(호스트명/포트/DSN)은 전부 환경변수로 외부화
- 서비스는 stateless. 상태는 DB/Kafka에만
- Actuator liveness/readiness probe → compose healthcheck, 나중에 k8s probe로 전환
- 고정 IP 금지. 서비스 이름(DNS)으로만 통신 (DB host `order-db`, 브로커 `kafka:9092`)
- **DB per service**: 각 서비스는 자기 DB만 소유. 남의 데이터는 ID + 이벤트로만 참조

---

## 서비스 / 포트

| 서비스 | 호스트 포트 | DB | DB 호스트 포트 | Swagger |
|---|---|---|---|---|
| order-service | 8080 | order-db | 3306 | http://localhost:8080/swagger-ui.html |
| product-service | 8081 | product-db | 3307 | http://localhost:8081/swagger-ui.html |
| payment-service | 8082 | payment-db | 3308 | http://localhost:8082/swagger-ui.html |
| kafka (브로커) | 9094 (호스트용 EXTERNAL) | — | — | — |

컨테이너 안에서는 브로커를 `kafka:9092`로, 맥에서 IDE로 직접 띄울 땐 `localhost:9094`로 접속한다.

## 아키텍처 — 비동기 Saga (현재)

서비스 간은 **동기 호출이 아니라 Kafka 이벤트**로 잇는다. 주문 1건이 세 서비스를 거치며 흐른다(choreography — 중앙 조정자 없음):

```
order   주문(PENDING) ─OrderCreated(amount,items)──▶ order-events
payment   결제 승인 ────PaymentProcessed(APPROVED,items)──▶ payment-events
product   재고 차감 ─────StockProcessed(DEDUCTED,이름/단가)──▶ product-events
order   주문 CONFIRMED (product가 준 실제 단가로 total 재계산)

보상① 결제 거절: PaymentProcessed(FAILED) ─▶ order 즉시 CANCELLED (재고 진입 안 함)
보상② 재고 실패: StockProcessed(FAILED)  ─▶ order CANCELLED + payment REFUNDED (이미 한 결제를 되돌림)
```

- 결제가 재고보다 **먼저**라, 주문 생성 시 클라가 보낸 **예상 단가**로 결제 금액(`amount`)을 만든다. 상품의 **진짜 이름/단가는 재고 차감 후 product가 채운다**(order는 상품을 JOIN하지 않는다).
- order·payment는 결과 이벤트를 **여러 타입** 구독하므로 타입별 컨슈머 팩토리(`KafkaConsumerConfig`)로 역직렬화를 분리한다.

## 실행 방법

```bash
# 1) 환경변수 준비
cp .env.example .env

# 2) 빌드 + 기동 (각 DB·kafka가 healthy해진 뒤 각 서비스가 뜬다)
docker compose up --build

# 3) 상태 확인 (다른 터미널에서) — 서비스3 + DB3 + kafka = 7개 컨테이너
docker compose ps
```

### 동작 확인 ① 정상 흐름 → CONFIRMED

```bash
# 1) 상품 등록 (product-service) — 주문하려면 먼저 상품·재고가 있어야 한다
#    응답의 data.productId를 확인해 아래 주문에 사용한다 (아래는 fresh start 기준 1, 2 가정)
curl -X POST http://localhost:8081/api/v1/products \
  -H 'Content-Type: application/json' \
  -d '{"name": "키보드", "price": 30000, "stockQuantity": 10}'
curl -X POST http://localhost:8081/api/v1/products \
  -H 'Content-Type: application/json' \
  -d '{"name": "마우스", "price": 15000, "stockQuantity": 1}'

# 2) 주문 생성 (order-service)
#    이제 productId, quantity + "예상 단가(unitPrice)"를 보낸다 (결제가 재고보다 먼저라 금액이 필요).
#    응답은 비동기라 PENDING으로 즉시 200 — 결제/재고는 이벤트로 뒤따라 처리된다.
curl -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId": 1, "items": [{"productId": 1, "quantity": 2, "unitPrice": 30000}]}'

# 3) 잠시 후 주문 조회 — PENDING → CONFIRMED, 이름/단가가 채워지고 total 재계산됨
curl http://localhost:8080/api/v1/orders/1

# 4) 결제 확인 (APPROVED), 재고 확인 (키보드 10 → 8)
curl http://localhost:8082/api/v1/payments
curl http://localhost:8081/api/v1/products/1
```

흐름을 로그로 보려면: `docker compose logs -f order-service payment-service product-service`
(`OrderCreated 발행 → 결제 승인 → 재고 차감 → 주문 확정` 한 바퀴가 보인다.)

### 동작 확인 ② 보상 — 결제 거절 

```bash
# 예상 단가를 결제 한도(기본 1,000,000) 초과로 보낸다 → 결제 FAILED
curl -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId": 1, "items": [{"productId": 1, "quantity": 1, "unitPrice": 2000000}]}'

# 결과: 주문 CANCELLED, 결제 FAILED, 키보드 재고는 그대로 (재고 단계 진입조차 안 함)
```

### 동작 확인 ③ 보상 사슬 — 재고 실패 → 결제 환불 (★ 4b 핵심)

```bash
# 재고(1)보다 많이(5) 주문하되 금액은 한도 이하 → 결제는 APPROVED 됐다가 재고에서 실패
curl -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId": 1, "items": [{"productId": 2, "quantity": 5, "unitPrice": 15000}]}'

# 결과: 주문 CANCELLED + 결제 APPROVED→REFUNDED + 마우스 재고 1 그대로
#   = 이미 한 결제를 거꾸로 되돌리는 보상 사슬
curl http://localhost:8082/api/v1/payments     # 해당 주문 결제가 REFUNDED
```

### 동작 확인 ④ 디커플링 / eventual consistency (product 죽였다 살리기)

```bash
docker compose stop product-service
# 주문 생성 → 여전히 200 (PENDING). step2였으면 503으로 실패했을 것.
curl -X POST http://localhost:8080/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1,"unitPrice":30000}]}'

docker compose start product-service
# product가 멈췄던 offset부터 이어받아 밀린 이벤트를 따라잡고, 주문이 PENDING → CONFIRMED로 수렴
```

### 더 관찰해볼 것
- **재고 부족 / 없는 상품**: product가 `PRODUCT_xxx`로 실패 → 보상으로 주문 취소(+결제 환불).
- **결제 한도 조정**: `.env`의 `PAYMENT_APPROVAL_LIMIT`를 낮춰 결제 거절(보상①)을 쉽게 유도.
- **consumer offset/lag**: `kafka-consumer-groups --group order-service --describe`로 어디까지 읽었나 확인.

### 정리
```bash
docker compose down        # 컨테이너 제거 (DB 데이터는 볼륨에 남음)
docker compose down -v     # 볼륨까지 삭제 (DB 데이터 초기화 — 상품 id가 1부터 다시 시작)
```