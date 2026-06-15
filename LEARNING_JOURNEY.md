# 학습 일지 — commerce-msa (모놀리식 → MSA 체득기)

> 해당 문서는 "무엇을 만들었나"가 아니라 **"어떻게 이해해 갔나"**를 기록

---

## 전체 단계별 흐름 

| 단계 | 직전의 고통 | 이번에 해소한 것 | 새로 생긴 고통 (→ 다음 단계) |
|---|---|---|---|
| 1 | (없음 — 시작) | Spring Boot를 compose로 띄우기, probe/healthcheck 습관 | 서비스가 1개라 "남의 DB", "장애 전파"를 못 느낌 |
| 2 | 단일 서비스라 결합/장애 전파를 못 겪음 | 서비스 2개 + DB per service + REST 동기 호출 | **"쟤 죽으면 나도 죽는다"** (시간 결합), 동기 호출이 트랜잭션을 길게 잡음 |
| 3a | 동기 호출의 시간 결합 | Kafka 도입 — 이벤트 발행/수신을 **추가만**(동기는 유지) | 발행은 하는데 정작 결합은 안 끊김(동기가 그대로라) |
| 3b | 동기 호출이 남아 결합이 안 끊김 | 동기 호출 **제거**, 완전 비동기 전환 | **주문은 됐는데 재고가 안 깎이는 불일치** (order는 모름) → step4 |
| 4a | 비동기 불일치(주문은 됐는데 재고 결과를 order가 모름) | **choreography Saga** — product가 결과를 역방향 이벤트로, order가 받아 `CONFIRMED`/`CANCELLED`(+ 이름/가격 채움) | 단계가 늘면 보상 사슬·중복 소비·발행 원자성 문제 → 4b~4e |
| 4b~4e (예정) | (4a에서 이어짐) | payment 추가 3-step saga + 보상 사슬 / orchestration 대조 / 멱등성 / Outbox | … |

---

## Step 1 — 서비스 1개 + DB 1개 


### 그때의 질문
"MSA 한다고 처음부터 서비스 여러개 띄우면 아무것도 못 배울것이라고 판단해, 일단 **하나를 제대로** compose로 띄우기"

### 무엇을 만들었나
- `order-service` 하나 + `order-db`(MySQL) 하나. DDD 구조 설계 
- 주문 도메인: `Order` + `OrderItem` + `OrderStatus`. 정적 팩토리 + no setter + 도메인 메서드로 상태 변경
- 공통 응답 봉투 `CommonResponse<T>`, 예외를 개별 클래스 대신 `ErrorCase`(enum) + 단일 `ApplicationException` 구조로 변경 

### 학습 장치 (1단계 목적)
- **설정 전부 환경변수로 외부화** — 호스트/포트/DSN을 코드에 박지 않음. `application.yml`엔 IDE용 기본값만 `${VAR:-default}`.
- **DB를 고정 IP가 아니라 DNS(`order-db`)로 접속** — 나중에 서비스 간 통신도 전부 이 습관
- **liveness / readiness probe 분리**:
  - `liveness` = 프로세스 살아있나(죽으면 재시작 대상)
  - `readiness` = 요청 받을 준비 됐나 — 여기에 **DB를 포함**시킴
- compose `healthcheck` + `depends_on: service_healthy` — DB가 healthy해진 뒤에 서비스가 뜨도록

### 직접 관찰한 것
```bash
docker compose stop order-db
curl http://localhost:8080/actuator/health/readiness   # → DOWN
```
- DB를 내리면 **readiness가 DOWN**으로 바뀐다 
- liveness는 그대로 UP 

### 배운 것
- **probe 두 개는 의미가 다르다.** 해당 구분이 2단계 "의존 대상이 죽으면 나도 못 받음"의 토대가 됨 
- 12-factor 습관(설정 외부화, stateless, DNS)은 **서비스가 1개일 때 미리 들여놓기** 

---

## Step 2 — 서비스 2개 + DB 2개, REST 동기 호출

**커밋 구간**: `feat: Product 엔티티 구현` ~ `feat: 상품 도메인에서 주문 도메인에 동기 호출 로직 연동 추가` (2026-06-11)

### 직전의 고통
서비스가 1개뿐이라 **"남의 DB는 JOIN 못 한다", "의존 서비스가 죽으면 나도 죽는다"**를 머리로만 알지 직접 느끼지 못함 → 두 번째 서비스가 있어야 결합과 장애 전파가 *실제로* 생김

### 무엇을 만들었나
- `product-service` + `product-db` 추가. product가 **상품/재고를 소유**.
- 재고 차감은 경쟁 자원이라 `find→set→save` 금지, **원자적 UPDATE**:
  `UPDATE products SET stock = stock - :n WHERE id = :id AND stock >= :n` → 0행이면 재고부족 에러
- order → product **REST 동기 호출**(`ProductClient`): 주문 생성 시 product를 호출해 재고를 깎고 이름/가격을 받아옴.
- `RestClient` **타임아웃**(connect 1s / read 2s) — 무한 대기 대신 빠른 실패

### MSA 경계 규칙을 처음으로 "지켜야 하는" 상황
- **DB per service**: order는 product의 테이블을 모른다. 상품 이름/가격/재고를 **JOIN으로 못 가져오고 API로 물어봐야** 한다.
- **남의 데이터는 ID + API로만**: `OrderItem.productId`는 FK가 아니라 그냥 ID 필드(product 소유 데이터 참조).

### 직접 관찰한 것 (2단계의 헤드라인)
```bash
docker compose stop product-service        # 의존 서비스만 내린다
curl -i -X POST localhost:8080/api/v1/orders -d '{...}'
#   → HTTP 503, code "ORDER_004" (상품 서비스를 호출할 수 없습니다)
```
- order는 살아있는데 **product가 죽으니 주문을 못 받는다.** = **시간 결합(temporal coupling)**: 둘이 *동시에* 살아있어야만 동작
- 타임아웃(2초) 덕에 무한 대기 없이 빠르게 503으로 떨어진다

### 일부러 남긴 구멍 
- `OrderService.createOrder`: **재고 차감(원격) 성공 후 주문 저장(로컬)이 실패하면** "재고만 줄고 주문은 없는" 불일치가 남는다. 2단계에선 일부러 안 막음 → 3·4단계 동기로 해결 

### 배운 것
- **동기 호출 = 강한 결합.** 응답을 받아야만 내 일이 끝나니, 상대의 생사·속도에 직접 묶임 
- "분산 시스템에서 다른 서비스 호출은 *반드시 실패할 수 있는 I/O*"라는 감각. 타임아웃/실패 번역(`ORDER_004`)이 선택이 아니라 필수.
- 해당 한계점이 비동기(Kafka)** 전환의 시작점이 됨 

---

## Step 3a — Kafka 도입 (이벤트 발행/수신, additive)

### 직전의 고통
2단계의 시간 결합("쟤 죽으면 나도 죽음"). 이걸 끊으려면 order가 product의 응답을 **안 기다려야** 한다. → 중간에 **메시지 브로커(Kafka)**를 두고, order는 "주문 생겼다"만 알려주고 끝냄. 

### 한 번에 안 바꾼 이유 
3a에서는 **동기 호출을 그대로 둔 채** 이벤트 발행/수신을 **추가만** 해서 **"발행/수신이 되는지"부터 격리해서 확인**하고 실제 전환(3b)은 다음으로 

### 무엇을 만들었나
- compose에 Kafka 1대(KRaft 단일 브로커, 주키퍼 없음). 리스너 2개:
  - `kafka:9092` — compose 네트워크 안 서비스용(DNS)
  - `localhost:9094` — 맥에서 IDE로 직접 띄울 때용
- order: 주문 저장 후 `OrderCreated`를 `order-events` 토픽에 **발행**(`OrderEventPublisher`). key=orderId로 같은 주문은 같은 파티션(순서 보장).
- product: `OrderEventListener`가 구독 → **로그만** 찍음(아직 재고차감 X).
- 직렬화: JSON. 서비스 간 이벤트 클래스를 공유하지 않고 각자 정의(`add.type.headers=false` + `trusted.packages`).

### 막힌 것 → [TROUBLESHOOTING.md TS-1](./TROUBLESHOOTING.md)
- `KAFKA_LISTENERS`에 `0.0.0.0`을 직접 박았더니 브로커가 안 떴다. → 호스트를 비워서(`://:9092`) 해결. **LISTENERS(바인드) vs ADVERTISED(광고)의 역할 차이**를 여기서 배움.

### 직접 관찰한 것
```bash
# 토픽에 메시지가 실제로 쌓이나 (컨슈머와 별개로 처음부터 읽기)
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic order-events --from-beginning

# 컨슈머 그룹이 어디까지 읽었나 (offset / lag)
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group product-service
```

### 배운 것
- **발행과 소비가 분리**된다 = order는 product가 받았는지 모르고/신경 안 쓴다.
- 메시지는 **토픽에 남는다(로그 구조)**. 컨슈머가 안 읽어도 사라지지 않고, **offset**으로 "어디까지 읽었나"가 관리된다. → 이게 3b "죽었다 살아나도 따라잡기"의 원리
- 다만 3a 시점엔 동기 호출이 **그대로 남아있어서**, 발행은 해도 결합은 아직 안 끊겼다. → 3b의 동기

---

## Step 3b — 동기 호출 제거, 완전 비동기 전환 

### 직전의 고통
3a까지 와도 **동기 호출이 그대로** 있으니 "product 죽으면 order 죽음"은 그대로였다. 진짜로 결합을 끊으려면 **동기 호출을 걷어내야** 함 

### 설계 결정 — "Thin order, 완전 비동기"
동기 호출을 없애면 order는 **주문 시점에 상품 이름/가격을 모른다**(그건 product 소유 데이터라서) 그래서:
- order `createOrder`: `ProductClient.deductStock` 동기 호출 **삭제**. 주문을 **CREATED**로 저장하고 `OrderCreated`만 발행.
- `OrderItem`은 `productId + quantity`만 → **`productName = null`, `unitPrice = 0`, `totalAmount = 0`**. ("주문은 아직 상품을 모른다"가 데이터로 드러남)
- product `OrderEventListener`: 로그만 찍던 걸 → 이벤트를 받아 **실제 재고 차감**(기존 `ProductService.deductStock` 재사용)
- 재고부족/상품없음 예외는 **삼켜서 로그만** 남김(던지면 무한 재시도로 파티션이 막히니까) → 불일치를 일부러 남김

### 막힌 것 → [TROUBLESHOOTING.md TS-2](./TROUBLESHOOTING.md)
- 첫 주문에서 `Column 'product_name' cannot be null` 500. thin 리팩터로 `productName=null`을 넣는데 옛 테이블 컬럼이 `NOT NULL`이었음(`ddl-auto: update`의 스키마 드리프트). `docker compose down -v`로 스키마 재생성해 해결.

### 직접 관찰한 것 

#### 1. 정상 흐름 — 비동기 디커플링
주문 응답은 **즉시 200**, 근데 상품 데이터는 비어있다:
```json
// POST /api/v1/orders  {"customerId":1,"items":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}
{"success":true,"data":{"orderId":1,"status":"CREATED","totalAmount":0,
  "items":[{"productId":1,"productName":null,"unitPrice":0,"quantity":2,"lineTotal":0},
           {"productId":2,"productName":null,"unitPrice":0,"quantity":1,"lineTotal":0}]}}
```
재고 차감은 주문 응답이 나간 **뒤에, 별도 컨슈머 스레드**에서:
```
[product-service] [ntainer#0-0-C-1] OrderEventListener : [product] 재고 차감 완료 ← orderId=1
```
재고 확인: 키보드 10 → 8, 마우스 10 → 9. **order는 이 차감을 기다리지도, 알지도 않는다.**

#### 2. 헤드라인 — product 죽여도 주문 성공 + 살아나서 따라잡기
```bash
docker compose stop product-service      # 소비자 kill 
# 그 상태에서 주문 → 여전히 200! (step2였으면 503으로 에러 발생)
# {"success":true,"data":{"orderId":2,"status":"CREATED", ...}}
docker compose start product-service     # 되살린다
```
product가 살아나자 컨슈머가 **죽기 전 멈췄던 offset부터 이어받아** 밀린 이벤트를 처리:
```
[product-service] ConsumerCoordinator : Successfully joined group with generation Generation{generationId=3 ...}
[product-service] ConsumerUtils       : Setting offset for partition order-events-0 to the committed offset
                                        FetchPosition{offset=1, ...}        ← 멈췄던 바로 그 자리
[product-service] OrderEventListener  : [product] OrderCreated 수신 ← orderId=2, items=[Item[productId=1, quantity=2]]
[product-service] OrderEventListener  : [product] 재고 차감 완료 ← orderId=2
```
재고: 8 → 6. **죽어있던 동안 쌓인 이벤트를 하나도 안 잃고 정확히 멈춘 자리(offset=1)부터** 따라잡음. 
- 이게 가능한 이유: `group-id=product-service`(고정) + `auto-offset-reset=earliest` + Kafka가 offset을 브로커에 커밋해두기 때문. 컨슈머가 죽어도 **메시지도, 읽은 위치도 브로커에 남는다.**

> **step2의 고통("쟤 죽으면 나도 죽음")을 반대로 뒤집음**
> step3b: "쟤가 죽어도 난 받고, 쟤는 살아나서 밀린 일을 따라잡는다." = **디커플링(공간) + 내구성(시간)**

#### 3. 의도적 구멍 — 비동기의 대가 (재고 부족 불일치)
재고(6)보다 훨씬 많은 9999개를 주문:
```bash
# POST /api/v1/orders  {"customerId":1,"items":[{"productId":1,"quantity":9999}]}
# {"success":true,"data":{"orderId":3,"status":"CREATED","quantity":9999, ...}}   ← 주문은 성공
```
```
[order-service]   OrderEventPublisher : [order] OrderCreated 발행 → topic=order-events, orderId=3
[product-service] OrderEventListener  : [product] OrderCreated 수신 ← orderId=3, items=[Item[productId=1, quantity=9999]]
[product-service] OrderEventListener  : [product] 재고 차감 실패 ← orderId=3, code=PRODUCT_002,
                                        msg=재고가 부족합니다. → 주문-재고 불일치 발생
```
재고는 6 그대로(원자적 UPDATE의 `WHERE stock >= 9999`가 0행). **주문은 CREATED인데 재고는 안 깎였고, order는 이 실패를 모른다.**
- 동기였다면 그 자리에서 거부했을 텐데, 비동기라 order는 이미 커밋하고 끝남

### 배운 것
- **비동기는 결합을 끊지만 완벽하지 않음** "응답을 안 기다린다" = "실패를 즉시 못 안다". 디커플링/내구성을 얻는 대신 **정합성(consistency)을 잃는다**
- 이 불일치를 어떻게 되돌릴까 — product가 실패를 **역방향 이벤트**로 알려주고 order가 주문을 취소하는 것 = **step4 Saga / 보상 이벤트**의 출발점
- 강한 일관성(strong) → **결과적 일관성(eventual consistency)**으로 바뀌는 지점. "지금 당장 맞다"가 아니라 "결국엔 맞춰진다(+ 틀어졌을 때 되돌리는 장치가 있다)"

---

## Step 4a — choreography Saga (역방향 보상 이벤트, order↔product)

### 직전의 고통
3b에서 비동기로 결합은 끊었지만 **"주문은 CREATED인데 재고 차감이 실패해도 order는 그걸 모른다"**는 구멍을 일부러 남겼다. 이름/가격도 `null`/`0`. order는 이벤트를 **쏘기만** 하는 단방향이었다.

### Saga란 — 왜 필요한가
DB per service라 모놀리식의 `@Transactional` 하나로 여러 서비스를 묶을 수 없다(2PC 분산 트랜잭션은 서로 락을 잡고 기다려 강결합이라 MSA에 부적합). **Saga = 각 서비스의 로컬 트랜잭션을 이벤트로 잇고, 중간에 실패하면 보상(compensation) 트랜잭션으로 되돌리는 패턴.** 강한 일관성을 포기하는 대신 느슨한 결합을 지키고 eventual consistency를 받아들인다.

### 설계 결정 — choreography (중앙 조정자 없음)
order에 **결과를 듣는 귀**를 달아 saga 루프를 닫는다:
- `OrderStatus`: `CREATED` → **`PENDING`**(재고 결과 대기 = saga 시작점). 결과에 따라 `CONFIRMED`/`CANCELLED`.
- `Order.confirm(snapshots)` / `Order.cancel()` 도메인 메서드. 성공 시 product가 준 이름/단가 스냅샷을 채우고 `totalAmount` 재계산.
- **새 토픽 `product-events`** — product가 자기가 구독 중인 `order-events`에 되쏘면 자기 이벤트를 자기가 먹으니 분리 필수.
- **order가 처음으로 Kafka consumer가 됨**(지금껏 producer만).
- 단일 토픽·단일 타입 `StockProcessedEvent`(결과 enum `DEDUCTED`/`FAILED`)로 3a의 `value.default.type` 직렬화 방식을 그대로 재사용.

흐름:
```
order: 주문 PENDING 저장 ─OrderCreated→ (order-events) → product
product: 재고 차감
   성공 ─StockProcessed(DEDUCTED, 이름·단가)→ (product-events) → order: CONFIRMED
   실패 ─StockProcessed(FAILED, 사유코드)────→ (product-events) → order: CANCELLED (보상)
```
3b에서 실패를 **삼키던** 걸 → 4a는 **FAILED 이벤트로 알린다**가 핵심 전환.

### 직접 관찰한 것

#### 1. 정상 주문 → CONFIRMED (루프 한 바퀴)
주문 직후 응답은 아직 PENDING(비동기 확정 전 = 올바름):
```json
// POST /api/v1/orders  {"customerId":1,"items":[{"productId":1,"quantity":2}]}
{"data":{"orderId":1,"status":"PENDING","totalAmount":0,
  "items":[{"productId":1,"productName":null,"unitPrice":0,"quantity":2,"lineTotal":0}]}}
```
ms 뒤 GET 하면 채워져서 확정:
```json
// GET /api/v1/orders/1
{"data":{"orderId":1,"status":"CONFIRMED","totalAmount":60000,
  "items":[{"productId":1,"productName":"키보드","unitPrice":30000,"quantity":2,"lineTotal":60000}]}}
```
로그(saga 한 바퀴):
```
[order]   OrderCreated 발행 → topic=order-events, orderId=1
[product] OrderCreated 수신 ← orderId=1
[product] StockProcessed 발행 → topic=product-events, orderId=1, result=DEDUCTED
[product] 재고 차감 완료 → orderId=1
[order]   StockProcessed 수신 ← orderId=1, result=DEDUCTED
  (order: UPDATE order_items SET product_name=?, unit_price=?   ← confirm()이 스냅샷을 채우는 순간)
[order]   주문 확정(CONFIRMED) → orderId=1
```
재고 100 → 98. **3b의 구멍(이름/가격 null·0)이 메워졌다.**

#### 2. 재고초과 주문 → CANCELLED (보상)
재고 50인 마우스를 999개 주문:
```
[product] OrderCreated 수신 ← orderId=2, items=[Item[productId=2, quantity=999]]
  (product: UPDATE products SET stock_quantity=(stock_quantity-?) WHERE id=? AND stock_quantity>=?   ← 원자적 UPDATE, 0행 갱신)
[product] 재고 차감 실패 ← orderId=2, code=PRODUCT_002 → StockProcessed(FAILED) 발행
[order]   StockProcessed 수신 ← orderId=2, result=FAILED
[order]   재고 실패로 주문 취소(CANCELLED) ← orderId=2, reason=PRODUCT_002
```
GET /orders/2 → `CANCELLED`, 마우스 재고는 50 그대로(안 깎임). **3b였으면 order가 몰랐을 실패를 보상으로 취소까지 반영.**

#### 3. product 죽였다 살리기 → PENDING 머물다 따라잡음 (eventual consistency)
```bash
docker compose stop product-service
# 주문3 생성 → 200, 하지만 GET → status PENDING (결과 줄 product가 죽어 루프가 안 닫힘 = 잠깐의 불일치)
docker compose start product-service
```
살아나며 커밋된 offset부터 이어받아 따라잡음:
```
[product] Setting offset for partition order-events-0 to the committed offset FetchPosition{offset=2}   ← 멈췄던 자리부터 재개
[product] OrderCreated 수신 ← orderId=3
[product] StockProcessed 발행 → orderId=3, result=DEDUCTED
[order]   주문 확정(CONFIRMED) → orderId=3
```
GET /orders/3 → PENDING → (몇 초 뒤) → `CONFIRMED`(total 30000, 키보드). `kafka-consumer-groups --group order-service` → `product-events  CURRENT 3 / END 3 / LAG 0`.
- **PENDING에 잠깐 머물렀다 결국 CONFIRMED = eventual consistency.** 잠깐 어긋났다 결국 맞춰지는 게 saga의 핵심 거래.

### 배운 것
- **Saga는 "여러 로컬 트랜잭션 + 보상"으로 분산 트랜잭션을 흉내낸다.** 각 서비스는 자기 DB만 ACID로 커밋하고, 그걸 이벤트로 잇는다. 실패하면 DB 롤백이 아니라 **반대 동작(취소)을 실행**하는 의미적 롤백.
- **결과를 "삼키지 않고 이벤트로 알린다"가 루프를 닫는 열쇠.** 3b의 단방향(쏘고 끝) → 4a의 양방향(쏘고 결과를 듣는다).
- **choreography는 단순하지만** 흐름이 양쪽 리스너에 흩어진다. 지금은 보상이 "주문 취소" 하나뿐이라 단순한데, 단계가 늘면(결제 등) **보상 사슬**이 생기고 추적이 어려워진다 → 4b(payment)에서 그 고통을 만나고, 4c(orchestration)에서 대조한다.
- **남은 한계**: 같은 이벤트가 두 번 오면 두 번 처리됨(멱등성 X → 4d), "DB 저장 + 이벤트 발행"이 한 트랜잭션이 아님(Outbox → 4e).

---

## 지금까지 관통하는 큰 그림

처음엔 "MSA = 서비스를 잘게 쪼개면 좋아진다"고 생각했지만, 실제로 겪어보니:

1. **쪼개는 순간 결합의 형태가 바뀐다.** 모놀리식의 in-process 호출이 → 네트워크 호출(실패 가능) → 메시지(비동기, 지연/불일치 가능)로. 매 단계가 **새로운 실패 모드**를 데려온다.
2. **각 인프라 조각은 "직전의 고통"에 대한 답이다.** Kafka를 "좋아 보여서" 넣은 게 아니라, 동기 호출의 시간 결합이 아파서 넣었다. 이 순서를 지켜야 *왜* 필요한지가 몸에 남는다.
3. **트레이드오프에 공짜가 없다.** 동기(일관성↑, 결합↑) ↔ 비동기(결합↓, 일관성↓). 어느 쪽도 정답이 아니고, **무엇을 포기할지 고르는 것**이 설계다.
4. **관찰 가능성(observability)이 학습의 핵심 도구** probe, healthcheck, 로그(`[order] 발행 → [product] 수신`), consumer offset/lag — 이게 없었으면 "비동기가 됐다"를 *믿을* 수만 있고 *볼* 수는 없었다.

---

## 다음 — Step 4b~4e 예고

4a로 choreography 보상 루프를 닫음. 남은 것:
- **4b — payment-service 추가**: 주문 → 결제 → 재고 **3-step saga**. "재고 실패 시 이미 한 결제를 환불"하는 **보상 사슬**이 생겨 saga의 사슬 직접적으로 확인
- **4c — orchestration 대조**: 같은 흐름을 중앙 조정자(orchestrator)로 재구현해 choreography와 트레이드오프 비교.
- **4d — 멱등성(idempotency)**: at-least-once 중복 소비로 재고가 두 번 깎이는 걸 막는다(처리한 이벤트 ID 기록).
- **4e — Outbox 패턴**: "DB 저장 + 이벤트 발행"이 한 트랜잭션이 아니라서 생기는 발행 원자성 문제를 outbox 테이블 + relay로 해결.
