# 트러블슈팅 기록 — commerce-msa

구현하면서 실제로 막혔던 지점과 **증상 → 원인 → 해결 → 교훈**을 기록 

## 목차

| ID | 날짜 | 단계 | 한 줄 요약 |
|---|---|---|---|
| [TS-2](#ts-2--주문-생성-시-column-product_name-cannot-be-null-http-500) | 2026-06-12 | step3b | 주문 생성 시 `Column 'product_name' cannot be null` (HTTP 500) |
| [TS-1](#ts-1--kafka-브로커-기동-실패-kafka_listeners에-0000) | 2026-06-11 | step3a | Kafka 브로커 기동 실패 (`KAFKA_LISTENERS`에 `0.0.0.0`) |

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