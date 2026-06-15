# 트러블슈팅 기록 — commerce-msa

구현하면서 실제로 막혔던 지점과 **증상 → 원인 → 해결 → 교훈**을 기록 

## 목차

| ID | 날짜 | 단계 | 한 줄 요약 |
|---|---|---|---|
| [TS-3](#ts-3--새-결제-서비스가-과거-이벤트를-재생해-유령-결제-생성) | 2026-06-15 | step4b | 새 payment가 `earliest`로 과거 이벤트 재생 → 유령 결제(amount=0) + 중복 소비 |
| [TS-2](#ts-2--주문-생성-시-column-product_name-cannot-be-null-http-500) | 2026-06-12 | step3b | 주문 생성 시 `Column 'product_name' cannot be null` (HTTP 500) |
| [TS-1](#ts-1--kafka-브로커-기동-실패-kafka_listeners에-0000) | 2026-06-11 | step3a | Kafka 브로커 기동 실패 (`KAFKA_LISTENERS`에 `0.0.0.0`) |

---

## TS-3 — 새 결제 서비스가 과거 이벤트를 재생해 유령 결제 생성

- **날짜**: 2026-06-15
- **단계**: step4b (payment 추가, 주문→결제→재고 3-step Saga)
- **관련 커밋**: — (관찰 사항. 근본 해결은 4d 멱등성 예정)

### 증상

4b 통합 관찰 중, **우리가 만들지 않은 결제가 결제 목록에 있었다.** 이번 세션에 넣은 주문은 4·5·6인데 1·2·3짜리 결제가 존재:

```bash
curl -s localhost:8082/api/v1/payments
# [{"orderId":1,"amount":0,"status":"APPROVED"},
#  {"orderId":2,"amount":0,"status":"REFUNDED"},
#  {"orderId":3,"amount":0,"status":"APPROVED"}, ... 4·5·6 ...]
```

order 1·2·3은 **이전 세션(4a)에 이미 끝난 주문**인데, 방금 추가한 payment가 이들에 결제를 만들어버렸다. payment 기동 직후 로그:

```
[payment] OrderCreated 수신 <- orderId=1, amount=0  → 결제 승인 paymentId=1
[payment] OrderCreated 수신 <- orderId=2, amount=0  → 승인 paymentId=2
[payment] OrderCreated 수신 <- orderId=3, amount=0  → 승인 paymentId=3
[payment] StockProcessed 수신 <- orderId=2, result=FAILED   ← (.516)
[payment] StockProcessed 수신 <- orderId=2, result=FAILED   ← (.677) 또 옴
[payment] 재고 실패로 결제 환불(REFUNDED) -> orderId=2        ← (.686)
[payment] StockProcessed 수신 <- orderId=2, result=FAILED   ← (.688) 또또 옴
[payment] 재고 실패로 결제 환불(REFUNDED) -> orderId=2        ← (.691)
```

컨슈머 그룹 오프셋도 처음부터 다 읽었다:

```
GROUP=payment-service  TOPIC=order-events    CURRENT-OFFSET=6  LOG-END-OFFSET=6  (offset 0부터 6까지 전부)
GROUP=payment-service  TOPIC=product-events  CURRENT-OFFSET=8  LOG-END-OFFSET=8
```

### 원인 — 세 가지가 겹침

1. **새 컨슈머 + `auto-offset-reset: earliest`**: payment는 새 consumer group이라 커밋된 오프셋이 없음 → `earliest`라 토픽을 **offset 0부터** 읽음. 볼륨(`down -v` 안 함)에 남아있던 과거 OrderCreated/StockProcessed를 **전부 재생** → 이미 완결된 주문에 유령 결제가 생김.
2. **이벤트 스키마 진화**: 과거 OrderCreated에는 `amount` 필드가 없었음(4b 조각1에서 추가). JSON 역직렬화 시 없는 필드는 기본값 → **amount=0**. 한도(100만) 이하라 전부 "0원 결제 승인"으로 통과. 계약(스키마)이 바뀌면 옛 이벤트가 *조용히* 잘못 해석된다.
3. **at-least-once 중복 소비**: orderId=2의 StockProcessed(FAILED)가 **3번** 소비되어 환불이 2번 호출됨.

### 해결 / 완화

- **즉시(데모 정리)**: `docker compose down -v`로 볼륨+토픽 데이터를 비우고 fresh start 
- **중복 환불은 이미 막혀 있었다**: `Payment.refund()`를 멱등으로 설계(APPROVED일 때만 REFUNDED, 이미 REFUNDED면 무시)해서 3번 와도 상태는 REFUNDED 하나로 수렴. → 중복 소비의 *피해*를 멱등성이 흡수
- **근본 해결(예정)**:
  - **멱등성(4d)**: 처리한 주문/이벤트 ID를 기록해 OrderCreated 재처리 시 유령 결제 자체를 막는다
  - **스키마 진화 전략**: 이벤트 버전 필드 또는 필수 필드(amount) 누락 시 거부/스킵
  - **컨슈머 합류 전략**: 운영에선 새 서비스가 과거를 통째로 재생하면 안 되는 경우가 많음 → `latest`로 시작하거나 의도된 백필만 허용

### 교훈

- **새 컨슈머를 기존 토픽에 붙이는 건 "지금부터"가 아니라 "태초부터"일 수 있다.** `earliest`면 토픽에 남은 역사 전체를 재생한다. 새 서비스를 saga에 끼울 때 *과거 이벤트를 어떻게 할지*가 명시적 설계 항목이다.
- **이벤트는 영속 계약이다.** 필드를 추가하면 옛 이벤트엔 그 필드가 없어 기본값(0/null)이 *조용히* 잘못된 동작(0원 결제)을 만든다. 스키마 진화엔 버전/검증이 필요
- **멱등성은 "있으면 좋은 것"이 아니라 at-least-once의 필수 짝.** 이번엔 refund 멱등 덕에 중복 환불을 면했다 — 4d가 왜 필요한지의 실측 증거
- 단계 사이에 `down -v`를 안 하면 이전 단계의 이벤트/데이터가 다음 단계 관찰을 오염시킨다(TS-2 스키마 드리프트와 같은 뿌리)

---

## TS-2 — 주문 생성 시 `Column 'product_name' cannot be null` (HTTP 500)

- **날짜**: 2026-06-12
- **단계**: step3b (동기 호출 제거 → OrderCreated 비동기 발행 전환)

### 증상

비동기 전환 후 정상 주문을 넣었는데 `CommonResponse` 가 아닌 **스프링 기본 500 에러**가 발생 

```bash
curl -s -X POST localhost:8080/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productId":5,"quantity":2},{"productId":6,"quantity":1}]}'
# {"timestamp":"...","status":500,"error":"Internal Server Error","path":"/api/v1/orders"}
```

order-service 로그:

```
WARN  o.h.engine.jdbc.spi.SqlExceptionHelper : SQL Error: 1048, SQLState: 23000
ERROR o.h.engine.jdbc.spi.SqlExceptionHelper : Column 'product_name' cannot be null
ERROR ... DataIntegrityViolationException: could not execute statement
       [Column 'product_name' cannot be null]
       [insert into order_items (order_id,product_id,product_name,quantity,unit_price) values (?,?,?,?,?)]
java.sql.SQLIntegrityConstraintViolationException: Column 'product_name' cannot be null
```

> 응답이 `CommonResponse`가 아니라 스프링 기본 에러 형식 = `GlobalExceptionHandler`가 못 잡는 예외(= `ApplicationException` 아님)가 터짐 -> 여기선 JPA/DB 레벨 제약 위반.

### 원인 — `ddl-auto: update`의 스키마 드리프트

1. `OrderItem`을 thin 팩토리(`productId` + `quantity`만)로 리팩토링하면서, `productName`/`unitPrice`는 **주문 생성 시점에 `null`/`0`으로** 둔다. (상품 이름/가격/재고는 product-service 소유 → order는 모름. 비동기 이벤트로 재고 차감만 위임.)

   ```java
   // OrderItem.java
   @Column
   private String productName;   // 생성 시점엔 product 소유 데이터를 모름 → null

   private OrderItem(Order order, Long productId, int quantity) {
       this.order = order;
       this.productId = productId;
       this.productName = null;   // ← null로 insert
       this.unitPrice = 0L;
       this.quantity = quantity;
   }
   ```

2. 그런데 `order_items.product_name` 컬럼은 **예전 스키마(항상 이름을 채우던 시절)에서 `NOT NULL`로 이미 생성**되어 있었음. 

3. `spring.jpa.hibernate.ddl-auto: update`는 **새 테이블/새 컬럼 추가만** 해줌
   → 엔티티는 nullable인데 실제 테이블은 옛 `NOT NULL` 그대로 → insert 시 DB가 거부했음 

**스키마 드리프트(schema drift)** 발생 

### 해결 — DB 스키마 재생성

도커 볼륨을 초기화해 테이블을 현재 엔티티 기준으로 새로 만듬 

```bash
docker compose down -v       
docker compose up -d
docker compose ps           
```

**데이터를 보존하고 싶다면** 볼륨을 지우는 대신 컬럼 제약만 직접 수정:

```bash
docker compose exec order-db mysql -uroot -p"$ORDER_DB_ROOT_PASSWORD" \
  -e "ALTER TABLE orderdb.order_items MODIFY product_name VARCHAR(255) NULL;"
```

### 교훈

- `ddl-auto: update`는 **엔티티 제약이 바뀌는 리팩터에는 스키마가 따라오지 못한다.** (이미 알고 있는 사실이지만 다시 한번 확인하기)
- **빠른 진단 팁**: 응답이 `CommonResponse` 봉투가 아니면 핸들러가 못 잡은 예외다. 로그에서 `Caused by` / 첫 번째 예외 줄을 봐야 진짜 원인이 보임 

---

## TS-1 — Kafka 브로커 기동 실패 (`KAFKA_LISTENERS`에 `0.0.0.0`)

- **날짜**: 2026-06-11
- **단계**: step3a (Kafka 도입 — 이벤트 발행/수신)

### 증상

`docker compose up` 시 Kafka 컨테이너가 정상 기동/healthy 상태로 올라오지 못함. 
(KRaft 단일 브로커, `apache/kafka:3.9.0` — 주키퍼 없음.)

### 원인 — 리스너 바인드 주소를 `0.0.0.0`으로 명시

처음엔 `KAFKA_LISTENERS`의 호스트를 `0.0.0.0`으로 설정했음: 

```yaml
# 문제가 된 설정
KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,EXTERNAL://0.0.0.0:9094
```

Kafka 리스너 설정에서 "모든 인터페이스에 바인드"는 **호스트를 비워서**(`PLAINTEXT://:9092`) 표현하는 게 표준 형태 -> 호스트에 `0.0.0.0`을 직접 넣는 형태는 이 이미지의 KRaft 구성에서 기동을 실패시킴 

### 해결 

호스트를 비워 모든 인터페이스에 바인드하도록 수정:

```yaml
# 수정 후
KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093,EXTERNAL://:9094
```

현재 `docker-compose.yml`의 Kafka 리스너 전체 구성:

```yaml
KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093,EXTERNAL://:9094
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,EXTERNAL://localhost:9094
KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,EXTERNAL:PLAINTEXT
KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
```

### 교훈 — `LISTENERS` vs `ADVERTISED_LISTENERS`는 역할이 다르다

이 둘을 헷갈리면 브로커가 안 뜨거나, 떠도 클라이언트가 접속을 못 함 

| 설정 | 의미 | 호스트에 무엇을 쓰나 |
|---|---|---|
| `KAFKA_LISTENERS` | **내가 바인드(수신)할 주소** | 호스트를 **비워서** 모든 인터페이스. `0.0.0.0` 직접 사용 ❌ |
| `KAFKA_ADVERTISED_LISTENERS` | **클라이언트가 나를 찾아올 주소** | 반드시 **라우팅 가능한 이름**. `0.0.0.0` 절대 ❌ |

- 우리 구성에서 advertised가 리스너 2개인 이유:
  - `PLAINTEXT://kafka:9092` → compose 네트워크 **안**의 서비스(order/product)가 DNS `kafka`로 접속.
  - `EXTERNAL://localhost:9094` → 맥(호스트)에서 IDE로 서비스를 직접 띄울 때 `localhost:9094`로 접속.
- 한 줄 요약: **"바인드는 넓게(빈 호스트), 광고는 구체적인 라우팅 이름으로."**

---

## 새 항목 추가 템플릿

새로 막힌 게 생기면 아래를 복사해 **맨 위(TS-2 위)** 에 추가하고, 목차 표에도 삽입 

```markdown
## TS-{N} — {한 줄 제목}

- **날짜**: YYYY-MM-DD
- **단계**: stepX
- **관련 커밋**: `해시` (메시지)

### 증상
(에러 메시지/로그/재현 curl)

### 원인
(왜 일어났는지 — 추측 말고 확인된 원인)

### 해결
(실제로 한 조치. 명령어/코드 diff)

### 교훈
(다음에 같은 걸 피하려면 / MSA 관점에서 배운 것)
```