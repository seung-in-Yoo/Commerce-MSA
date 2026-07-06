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
| 4b | 보상이 "주문 취소" 하나뿐 — 보상 사슬·스타일 대조가 안 보임 | **payment 추가 3-step Saga** — 주문→결제→재고, 재고 실패 시 **결제 환불 + 주문 취소** 보상 사슬 | 흐름이 5개 리스너에 흩어져 추적이 어려움 → 4c |
| 4c | choreography는 "다음에 뭘 할지"가 각 서비스에 흩어져 전체 흐름이 코드 어디에도 없음 | **orchestration** — 중앙 오케스트레이터가 command/reply로 전 단계를 지휘, 흐름이 한 클래스로 모임 | 흐름은 모였으나 멱등성 X·발행 원자성 X는 그대로 → 4d~4e |
| 4d | 4c에서 흐름은 한 곳에 모였지만 Kafka at-least-once라 같은 명령/응답이 재배달되면 결제·재고차감·환불·재고명령이 **두 번** 일어남 | **멱등 소비(inbox)** — `messageId`를 PK로 `processed_messages`에 기록하고 **부수효과와 같은 트랜잭션**으로 묶어 재배달돼도 한 번만 처리 | DB저장+발행이 여전히 비원자(dual-write) → 4e |
| 4e (예정) | (4d에서 이어짐) | Outbox | … |

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

## Step 4b — payment 추가, 주문→결제→재고 3-step choreography Saga (보상 사슬)

### 직전의 고통
4a는 order↔product 2단계라 보상이 **"재고 실패 → 주문 취소" 하나뿐**이었다. 되돌릴 단계가 하나뿐이니 보상 **사슬**(여러 완료 단계를 역순으로 푸는 것)도, choreography vs orchestration **대조**도 드러나지 않는다. Saga의 진짜 맛(이미 끝낸 작업을 거꾸로 되돌리기)을 보려면 단계가 하나 더 필요했다.

### 설계 결정
- **결제를 재고보다 *앞*에 놓는다.** 보상은 *이미 완료된* 단계를 되돌리는 것이다. "재고 실패 시 결제 환불"을 보려면 결제가 재고보다 먼저 끝나 있어야 한다(결제가 맨 뒤면 되돌릴 게 없어 시시해짐). → 순서: 주문 → **결제** → 재고.
- **그래서 order에 "예상 단가"를 추가.** thin order(3b)는 가격을 몰랐는데, 결제가 재고보다 먼저라 **결제 시점에 청구 금액**이 필요하다. 클라가 예상 단가를 보내 `amount`를 만들고, **진짜 단가는 재고 차감 후 product가 채운다**(4a 그대로). → "예상가로 결제 → 실제가로 확정"이라는 작은 불일치가 자연히 생김(→ 4d 거리).
- **데이터는 이벤트를 타고 흐른다.** product가 이제 OrderCreated가 아니라 PaymentProcessed를 받으니, 재고 차감에 필요한 `items`를 payment가 결과 이벤트에 실어 다음 단계로 넘긴다.
- **보상은 두 갈래.** ① 결제 거절 → order가 즉시 취소(재고 진입 안 함). ② 재고 실패 → order 취소 **+ payment 환불**(이미 한 결제를 REFUNDED로 되돌림). 각 서비스가 실패 이벤트를 *독립적으로* 구독해 반응 = choreography.

흐름:
```
order:   주문 PENDING ─OrderCreated(amount,items)→ (order-events) → payment
payment: 결제 시도
  승인 ─PaymentProcessed(APPROVED, items)→ (payment-events) → product: 재고 차감
  거절 ─PaymentProcessed(FAILED)─────────→ (payment-events) → order: CANCELLED (보상①)
product: 재고 차감
  성공 ─StockProcessed(DEDUCTED, 이름·단가)→ (product-events) → order: CONFIRMED
  실패 ─StockProcessed(FAILED)────────────→ (product-events) → order: CANCELLED + payment: REFUNDED (보상②)
```

### 무엇을 만들었나 
- **1 · order**: `OrderLineRequest.unitPrice`(예상 단가) → `Order.totalAmount`/`OrderCreatedEvent.amount`.
- **2 · payment**: OrderCreated 구독 → `pay()`(한도 이하 APPROVED) → PaymentProcessed 발행 + `payment-events` 토픽.
- **3 · product**: 재고 차감 트리거를 OrderCreated → PaymentProcessed(APPROVED)로 재배선. APPROVED만 차감, FAILED는 무시. (죽은 OrderCreated 사본/리스너 삭제.)
- **4 · 보상**: `PaymentStatus.REFUNDED` + `Payment.refund()`(멱등). payment가 product-events(FAILED) 구독→환불, order가 payment-events(FAILED) 구독→취소.
- **5 · compose**: payment-service ↔ Kafka 연결(`KAFKA_BOOTSTRAP_SERVERS`, `depends_on: kafka`).

### 막힌 것 / 새 개념 — 멀티타입 컨슈머
order·payment가 이제 **이벤트 타입을 2개씩** 구독한다(order: StockProcessed+PaymentProcessed / payment: OrderCreated+StockProcessed). 그런데 3a부터 쓰던 `spring.json.value.default.type`은 **한 타입만** 지정 가능 → 한 컨슈머가 두 타입을 못 받는다.
→ `KafkaConsumerConfig`에 **타입별 `ConcurrentKafkaListenerContainerFactory`**(각각 `new JsonDeserializer<>(type, false)` = 타입 헤더 무시)를 만들고, 리스너마다 `@KafkaListener(containerFactory=...)`로 붙였다. (서비스 간 클래스 비공유 전제 유지)

### 직접 관찰한 것
docker compose로 7개 컨테이너(서비스3 + DB3 + kafka) 띄우고 3종:

#### 1. 정상 → CONFIRMED
키보드(재고10) 2개, 예상 6만원:
```
[order]   OrderCreated 발행 → orderId=4
[payment] OrderCreated 수신 → 결제 승인(APPROVED) paymentId=4
[product] PaymentProcessed(APPROVED) 수신 → 재고 차감 완료
[order]   StockProcessed(DEDUCTED) 수신 → 주문 확정(CONFIRMED)
```
주문 CONFIRMED(total 60000, 이름 null→키보드), 결제 APPROVED, 재고 10→8.

#### 2. 결제 거절 → 보상① (주문 취소, 재고 무손)
키보드 1개인데 예상 단가 200만원(한도 100만 초과):
```
[payment] 결제 거절(FAILED) → orderId=5
[order]   결제 거절로 주문 취소(CANCELLED)
```
주문 CANCELLED, 결제 FAILED, **재고 8 그대로**(재고 단계 진입조차 안 함).

#### 3. 재고 실패 → 보상② 사슬 (결제 환불 + 주문 취소) 
마우스(재고1) 5개, 예상 7.5만원(한도 이하):
```
[payment] OrderCreated 수신 → 결제 승인(APPROVED) paymentId=6
[product] PaymentProcessed(APPROVED) 수신 → 재고 차감 실패(PRODUCT_002, 5>1) → StockProcessed(FAILED) 발행
[order]   StockProcessed(FAILED) 수신 → 주문 취소(CANCELLED)
[payment] StockProcessed(FAILED) 수신 → 재고 실패로 결제 환불(REFUNDED)
```
주문 CANCELLED, 결제 **APPROVED→REFUNDED**, 마우스 재고 1 그대로. **이미 한 결제가 거꾸로 되돌려지는 것 확인**

### 배운 것
- **보상 사슬 = 성공한 단계들을 역순으로 푼다.** 단계가 N개면 어디서 깨지든 그 *앞의 완료된* 단계만큼 보상이 필요. 4a의 1-보상 → 4b의 2-보상으로 늘며 "사슬"이 처음 보였다.
- **choreography에선 데이터가 이벤트를 타고 흘러야 한다.** product를 결제 뒤로 옮기니 `items`를 payment가 중계해야 했다. 흐름을 바꾸면 페이로드도 따라 재설계된다.
- **한 컨슈머가 여러 이벤트 타입을 받는 순간 단일 `default.type`이 깨진다** → 타입별 컨테이너 팩토리로 분리.
- **choreography의 대가**: 흐름이 5개 리스너(order 2·payment 2·product 1)에 흩어져 "지금 어디서 뭐가 도는지"를 한눈에 못 본다. 로그를 `orderId`로 grep해야 사슬이 보인다 → **4c orchestration(중앙 조정자)과 대조**할 동기.
- **남은 한계**: 예상가↔실제가 불일치, 같은 이벤트 중복 소비(멱등성 X → 4d), DB저장+발행 비원자성(Outbox → 4e).

---

## Step 4c — orchestration Saga (중앙 오케스트레이터 + command/reply)

### 직전의 고통
4b(choreography)는 **"주문→결제→재고+보상"이라는 한 트랜잭션이 5개 리스너(order 2·payment 2·product 1)에 흩어져** 있었다. "지금 어디서 뭐가 도는지"를 보려면 파일을 다 열어 머릿속으로 이어붙여야 했고, **전체 흐름이 코드 어디에도 없다**는 게 핵심 

### 핵심 개념 — choreography vs orchestration
| | choreography (4b) | orchestration (4c) |
|---|---|---|
| 다음 단계를 누가 결정? | **아무도 안 함** — 각자 이벤트 듣고 스스로 판단 | **오케스트레이터가 결정** — 명령을 내림 |
| 전체 흐름이 보이는 곳 | 없음(리스너에 분산) | **오케스트레이터 한 클래스** |
| 토픽 성격 | 알림(event): "~가 일어났다" | **명령(command)/응답(reply)**: "~해라" / "~됐다" |
| 서비스끼리 아는 사이? | payment가 "내 다음은 product"임을 암묵적으로 앎 | payment/product는 **오케스트레이터만** 알면 됨 |

choreography의 목적은 **같은 흐름을 중앙 조정자로 재구현해 두 스타일을 대조**하는 것(기능은 4b와 동일)

### 설계 결정
- **토픽을 알림 → 명령/응답으로 재설계**: `payment-commands`/`payment-replies`/`stock-commands`/`stock-replies`. 환불은 결제 명령과 타입이 달라 별도 토픽 `payment-refund-commands`로 분리(토픽당 타입 1개 → typed factory가 깔끔).
- **오케스트레이터(`OrderSagaOrchestrator`)를 order에 내장**: 사가를 시작하는 서비스가 조정도 맡음. 새 서비스를 안 만들어 부담↓(학습 선택). *트레이드오프*: order에 결합되고, 규모 커지면 "조정자가 어느 서비스에 있나" 찾기 어려움 → 그땐 **전용 orchestrator-service + 분산추적(5단계)**이 정석. ("중앙"은 시스템 전체가 아니라 **사가 1개당 1개**.)
- **순환참조 회피**: `OrderService`가 사가 시작(`orchestrator.start`)으로 오케스트레이터를 의존하므로, 거꾸로 오케스트레이터는 `OrderService` 대신 **`OrderRepository`를 직접** 써서 주문 확정/취소를 처리(서로 물면 스프링 기동 실패).
- **payment/product는 일꾼으로 단순화**: "명령 받아 처리 → 응답"만. "다음에 뭘 할지" 판단 책임이 전부 오케스트레이터로 이동(product의 "결제 APPROVED인지" 가드도 사라짐).

오케스트레이터 = 작은 상태기계(메서드 3개 = 사가 3단계):
```
start          → 결제 명령(ProcessPayment)
onPaymentReply → 승인: 재고 명령(DeductStock) / 거절: 주문 취소
onStockReply   → 차감성공: 주문 확정(CONFIRMED) / 실패: 환불 명령(RefundPayment) + 주문 취소
```

### 무엇을 만들었나 (조각별)
- **1 · 어휘**: command(ProcessPayment/DeductStock/RefundPayment) + reply(PaymentProcessedReply/StockProcessedReply) record, `SagaTopics`, 토픽 5개 선언.
- **2 · 시작점**: `createOrder` → `OrderCreated` 발행을 **`orchestrator.start()` 호출로 교체** + `SagaCommandPublisher`.
- **3 · payment**: `order-events` 구독을 → `payment-commands` 구독으로, `payment-events` 발행을 → `payment-replies`로.
- **4 · 오케스트레이터 결제 응답**: `payment-replies` 구독 → 승인 시 재고 명령 / 거절 시 취소.
- **5 · product**: `payment-events` 구독을 → `stock-commands`로, `product-events` 발행을 → `stock-replies`로.
- **6 · 오케스트레이터 재고 응답**: `stock-replies` 구독 → 확정 / 실패 시 환불 명령 + 취소.
- **7 · 환불 보상**: payment `RefundCommandListener`(`payment-refund-commands` 구독 → 환불).
- **8 · 정리**: 4b choreography 잔재(event/publisher/listener, `OrderService.confirm/cancel`) 전면 제거.

### 새 개념 / 재사용 — typed factory (TS-4 패턴 복귀)
오케스트레이터는 `payment-replies`+`stock-replies`, payment는 `payment-commands`+`payment-refund-commands` — **타입이 다른 토픽을 둘씩 구독**한다. 단일 `value.default.type`은 한 타입만 → 4b에서 배운 **타입별 `containerFactory`**를 그대로 적용. (product는 `stock-commands` 하나뿐이라 default로 충분 → 비대칭이 "필요할 때만 도입"을 보여줌.)

### 직접 관찰한 것
docker 7개 컨테이너 + 상품 등록(키보드 30000/재고100, 마우스 15000/재고100) 후 3종. **로그에서 `[order]`(오케스트레이터)가 매 단계 "결정"을 주도**하는 게 4b의 "각자 알아서"와 대조된다.

#### 1. 정상 → CONFIRMED (키보드 2개, amount 6만)
```
[order]   사가 시작 -> 결제 명령 결정 orderId=1, amount=60000
[order]   ProcessPayment 명령 발행 -> topic=payment-commands, orderId=1
[payment] ProcessPayment 명령 수신 <- orderId=1, amount=60000
[payment] PaymentProcessed 응답 발행 -> topic=payment-replies, orderId=1, result=APPROVED
[order]   PaymentProcessed 응답 수신 <- orderId=1, result=APPROVED
[order]   DeductStock 명령 발행 -> topic=stock-commands, orderId=1
[order]   결제 승인 -> 재고 차감 명령 결정 orderId=1
[product] DeductStock 명령 수신 <- orderId=1, items=[Item[productId=1, quantity=2]]
[product] StockProcessed 응답 발행 -> topic=stock-replies, orderId=1, result=DEDUCTED
[order]   StockProcessed 응답 수신 <- orderId=1, result=DEDUCTED
[order]   재고 차감 성공 -> 주문 확정(CONFIRMED) orderId=1
```
GET /orders/1 → `CONFIRMED, total 60000, 키보드/30000`. 재고 100→98.

#### 2. 결제 거절 → CANCELLED (보상①, 재고 무손) (키보드 40개, amount 120만 > 한도)
```
[payment] ProcessPayment 명령 수신 <- orderId=2, amount=1200000
[payment] PaymentProcessed 응답 발행 -> topic=payment-replies, orderId=2, result=FAILED
[payment] 결제 거절(FAILED) -> orderId=2, amount=1200000
[order]   PaymentProcessed 응답 수신 <- orderId=2, result=FAILED
[order]   결제 거절 -> 주문 취소(CANCELLED) orderId=2, reason=PAYMENT_LIMIT_EXCEEDED
```
GET /orders/2 → `CANCELLED`. 재고 98 그대로, **`[product]` 로그가 아예 없음**(재고 단계 진입조차 안 함).

#### 3. 재고 실패 → CANCELLED + REFUNDED (보상② 사슬) (키보드 999개·단가 1000, amount 99.9만 ≤ 한도지만 999 > 재고 98)
```
[payment] 결제 승인(APPROVED) -> orderId=3, paymentId=2        ← 결제는 이미 일어남
[order]   PaymentProcessed 응답 수신 <- orderId=3, result=APPROVED
[order]   DeductStock 명령 발행 -> topic=stock-commands, orderId=3
[product] DeductStock 명령 수신 <- orderId=3, items=[Item[productId=1, quantity=999]]
[product] 재고 차감 실패 -> orderId=3, code=PRODUCT_002 -> StockProcessed(FAILED) 응답
[product] StockProcessed 응답 발행 -> topic=stock-replies, orderId=3, result=FAILED
[order]   StockProcessed 응답 수신 <- orderId=3, result=FAILED
[order]   RefundPayment 명령 발행 -> topic=payment-refund-commands, orderId=3
[order]   재고 실패 -> 결제 환불 명령 + 주문 취소(CANCELLED) orderId=3, reason=PRODUCT_002
[payment] RefundPayment 명령 수신 <- orderId=3
[payment] 결제 환불(REFUNDED) -> orderId=3
```
GET /orders/3 → `CANCELLED`, 결제 `REFUNDED`, 재고 98 그대로. **오케스트레이터가 "환불해라"라고 명령**해서 이미 한 결제가 되돌려진다(4b는 payment가 product-events를 직접 듣고 스스로 환불했음 — 판단 주체가 다름)

### 배운 것
- **orchestration = "흐름을 한 곳에 모은다".** 파일 개수는 4b와 비슷하지만, "다음에 뭘 할지"의 결정이 `OrderSagaOrchestrator` 한 클래스에 모여 **"전체 흐름이 어디 있냐"의 답이 한 곳**이 된다. 이게 choreography의 "추적 어려움"에 대한 답
- **command(명령) vs event(알림)는 의도가 다르다.** event는 "~가 일어났다"(받는 쪽이 알아서), command는 "~해라"(보내는 쪽이 흐름을 쥠). 같은 Kafka지만 토픽의 *의미*가 바뀐다
- **판단 책임의 이동.** 4b에선 각 서비스가 "내 다음 단계"를 알아야 했는데(결합), 4c에선 payment/product가 오케스트레이터만 알면 돼서 서로 모른다 → 서비스는 더 단순·독립적, 대신 오케스트레이터가 비대해짐(트레이드오프)
- **트레이드오프 정리**: choreography(분산·단순한 서비스·흐름 추적 어려움) ↔ orchestration(중앙·흐름 명확·조정자 단일 책임 집중/SPOF 성격). 어느 쪽도 정답 아님 — 흐름 복잡도·팀 구조에 따라 고른다
- **남은 한계는 그대로**: 예상가<->실제가, 중복 소비(멱등성 X → 4d), DB저장+발행 비원자성(Outbox → 4e). command/reply 발행도 트랜잭션과 별개(dual-write)라 4e 대상

---

## Step 4d — 멱등 소비 (inbox / 중복 배달 차단)

### 직전의 고통
4c로 흐름은 한 클래스에 모였지만, **Kafka는 at-least-once**다. 네트워크 끊김·컨슈머 리밸런싱·offset 커밋 실패 등으로 **같은 메시지가 두 번 배달**될 수 있다. 그런데 4c의 리스너들은 받은 메시지를 무조건 처리한다 → 결제 명령 재배달 = **이중 결제**, 재고 명령 재배달 = **이중 차감**, 환불 명령 재배달 = **이중 환불**, 그리고 결제 응답 재배달 = **재고 차감 명령이 또 발행되는 연쇄 이중 트리거**. "정확히 한 번"을 브로커가 보장 못 하니, **소비하는 쪽이 멱등**해야 한다.

### 핵심 개념 — 멱등 소비 = 메시지 단위 식별자 + inbox
- **멱등(idempotent)**: 같은 연산을 여러 번 해도 결과가 한 번 한 것과 같다. 우리가 원하는 건 "같은 메시지를 N번 받아도 부수효과는 1번".
- **방법**: 메시지마다 고유 `messageId`(UUID)를 부여하고, 처리한 messageId를 **inbox 테이블(`processed_messages`)에 PK로 기록**한다. 메시지를 받으면 먼저 `existsById(messageId)`로 검사 → 이미 있으면 **즉시 return(부수효과 0)**, 없으면 처리하고 기록.
- **결정적 포인트 — 부수효과와 inbox 기록을 같은 트랜잭션으로 묶는다.** 결제 저장과 `ProcessedMessage` 저장이 한 `@Transactional` 안에 있어야, "처리는 했는데 기록 직전에 죽어서 다음 재배달 때 또 처리"되는 틈이 없다. (둘 중 하나만 커밋되면 멱등이 깨진다.)
- **멱등 키는 orderId가 아니라 messageId 단위.** 같은 주문이라도 "다른 메시지"면 다른 일이므로 막으면 안 된다. 막는 기준은 *메시지 1건의 정체성*이다(→ 아래 반증).

### 설계 결정
- **inbox는 서비스마다 자기 DB에** (`order-db`/`product-db`/`payment-db` 각각 `processed_messages`). 멱등은 "이 컨슈머가 이 메시지를 처리했나"의 로컬 문제 → DB per service 원칙 유지.
- **모든 command/reply record에 `messageId` 필드 추가**(발행 시 `create(...)`에서 `UUID.randomUUID()` 부여). 멱등 키를 메시지 페이로드에 실어 보낸다.
- **가드를 4곳에 배치**(= 부수효과가 있는 컨슈머 전부):
  | 토픽 | 가드 위치 | 막는 것 |
  |---|---|---|
  | `payment-commands` | `PaymentCommandListener` | 이중 결제 |
  | `stock-commands` | `StockCommandHandler` | 이중 재고 차감 |
  | `payment-replies` | `OrderSagaOrchestrator.onPaymentReply` | 재고 명령 이중 발행(연쇄 트리거) |
  | `payment-refund-commands` | `RefundCommandListener` | 이중 환불 |
- **재고 차감만 listener에서 트랜잭션 핸들러(`StockCommandHandler`)로 분리**: "차감+inbox 기록"은 한 트랜잭션이어야 하지만, **재고 부족(FAILED) 응답 발행은 트랜잭션 밖**에서 해야 한다(롤백돼야 할 차감과 분리). 그래서 핸들러는 `Optional`을 돌려주고(중복=`empty`), listener가 그 결과로 응답을 발행/스킵한다.
- **실패는 inbox에 기록하지 않는다**(의도). 재고 부족은 영속 효과가 없으니(롤백), messageId를 안 남긴다 → 재배달돼도 "다시 실패"할 뿐 이중 부수효과가 없다. inbox는 *성공한 부수효과*만 보호하면 된다.

### 무엇을 만들었나 (조각별)
- **1 · 멱등 키**: 5개 command/reply record에 `messageId` 추가 + 정적 팩토리에서 UUID 발급
- **2 · inbox 엔티티**: 각 서비스에 `ProcessedMessage`(`message_id` PK length 36 + `processed_at`) + `ProcessedMessageRepository`
- **3 · 결제 가드**: `PaymentCommandListener`에 `existsById` 검사 + 결제·기록 한 트랜잭션
- **4 · 재고 가드**: `StockCommandHandler` 신설(차감+기록 트랜잭션, 중복은 `Optional.empty`), listener는 응답만
- **5 · reply 가드**: `OrderSagaOrchestrator`의 `onPaymentReply`/`onStockReply`에 검사 + 주문상태변경·기록 한 트랜잭션 → **연쇄 이중 트리거 차단**
- **6 · 환불 가드**: `RefundCommandListener`에 검사 + 환불·기록 한 트랜잭션

### 직접 관찰한 것
**테스트 방법**: 정상 주문 1건(orderId=4)을 CONFIRMED까지 흘려 messageId 4개를 로그에서 확보한 뒤, **그 messageId 그대로 다시 produce**해 "이미 처리됨 → 스킵"을 확인

#### 0. 정상 1바퀴 → CONFIRMED (키보드 2개, amount 10만, productId=3 재고 100→98)
```
[payment] ProcessPayment 명령 수신 <- messageId=1d848713-…-3b4cb6fce384, orderId=4, amount=100000
[payment] 결제 승인(APPROVED) -> orderId=4, paymentId=4
[order]   PaymentProcessed 응답 수신 <- messageId=23dd4191-…-4b93d9e74d0e, orderId=4, result=APPROVED
[order]   결제 승인 -> 재고 차감 명령 결정 orderId=4
[product] DeductStock 명령 수신 <- messageId=75ed1921-…-365458a51c10, orderId=4, items=[Item[productId=3, quantity=2]]
[product] 재고 차감 완료 -> orderId=4
[order]   StockProcessed 응답 수신 <- messageId=efaac8c0-…-049446667747, orderId=4, result=DEDUCTED
[order]   재고 차감 성공 -> 주문 확정(CONFIRMED) orderId=4
```
→ 메시지별 messageId: **결제명령** `1d848713` / **결제응답** `23dd4191` / **재고명령** `75ed1921` / **재고응답** `efaac8c0`.

#### 1. 이중 결제 차단 — `payment-commands`에 결제명령(messageId 1d848713) 재생
```
[payment] ProcessPayment 명령 수신 <- messageId=1d848713-…, orderId=4, amount=100000
[payment] 중복 메시지 스킵(이미 처리됨) -> messageId=1d848713-…, orderId=4
```
`SELECT COUNT(*) FROM payments WHERE order_id=4` → **재생 전 1, 재생 후 1**. `paymentService.pay()` 자체가 호출되지 않음

#### 2. 연쇄 이중 트리거 차단 — `payment-replies`에 결제응답(messageId 23dd4191) 재생
```
[order] PaymentProcessed 응답 수신 <- messageId=23dd4191-…, orderId=4, result=APPROVED
[order] 중복 응답 스킵(이미 처리됨) -> messageId=23dd4191-…, orderId=4
```
**결정적 증거**: 스킵 직후 `결제 승인 -> 재고 차감 명령 결정` / `DeductStock 명령 발행` 로그가 **안 뜬다** = 재고 차감 명령이 다시 발행되지 않음. 응답 한 번 중복 → 사가가 한 칸 더 굴러가는 것을 막음 

#### 3. 이중 재고 차감 차단 — `stock-commands`에 재고명령(messageId 75ed1921) 재생
```
[product] DeductStock 명령 수신 <- messageId=75ed1921-…, orderId=4, items=[Item[productId=3, quantity=2]]
[product] 중복 메시지 스킵(이미 처리됨) -> messageId=75ed1921-…, orderId=4
```
`SELECT stock_quantity FROM products WHERE id=3` → **재생 전 98, 재생 후 98**. 추가 차감 없음.

> (예정) **반증 테스트** — messageId만 새 값으로 바꿔 같은 결제명령을 쏘면 가드를 통과해 `payments`가 2건이 된다(이중 결제). "멱등은 orderId가 아니라 messageId 단위"임을 역으로 증명. **가드 D(이중 환불)** 는 재고 부족 주문으로 보상(환불)을 발생시킨 뒤 그 환불 messageId를 재생해 확인.

### 배운 것
- **at-least-once의 책임은 컨슈머로 넘어온다.** 브로커가 "정확히 한 번"을 못 주니, "여러 번 받아도 한 번"을 *소비 쪽이* 만든다. 이게 멱등 소비(inbox 패턴).
- **멱등의 핵심은 검사 자체가 아니라 "부수효과 + 기록"의 원자성.** 같은 트랜잭션으로 안 묶으면 처리 후/기록 전 죽었을 때 틈이 생긴다. inbox는 "기록도 부수효과의 일부"라는 발상.
- **멱등 키 = 메시지 1건의 정체성(messageId).** orderId로 막으면 정당한 후속 메시지까지 막힌다. 무엇을 "같은 일"로 볼지가 키 설계.
- **reply도 멱등 대상이다.** 명령(결제/차감/환불)만 생각하기 쉽지만, 오케스트레이터가 받는 **응답이 중복되면 다음 명령이 이중 발행**된다(연쇄). 부수효과(=다음 명령 발행)가 있는 모든 컨슈머가 가드 대상.
- **실패는 기록하지 않는 게 맞다.** inbox는 성공한 영속 효과만 보호하면 된다. 실패는 재배달돼도 다시 실패할 뿐이라 이중 효과가 없다 — 무조건 다 기록하는 게 아니라 "되돌릴 수 없는 부수효과"를 가진 경로만.
- **남은 한계**: 부수효과 저장과 reply/command **발행은 여전히 별개**(dual-write) — DB 커밋 후 발행 직전에 죽으면 사가가 멈춘다. inbox는 "중복"을 막지만 "발행 누락"은 못 막는다 → **4e Outbox**.

---

## Step 4e — Outbox 패턴 (저장과 발행의 원자성 / dual-write 해소)

### 직전의 고통
4d로 "중복 소비"는 막았지만, **메시지를 Kafka로 보내는 일 자체가 비즈니스 트랜잭션 밖**에 있었다. `createOrder`를 보면:

```java
@Transactional
public OrderResponse createOrder(...) {
    orderRepository.save(order);   // 1. 로컬 DB (커밋은 메서드 끝나야)
    orchestrator.start(saved);     // 2. kafkaTemplate.send — @Transactional 무시하고 즉시 브로커로
}
```

`save`(DB)와 `send`(Kafka)는 **서로 다른 두 시스템에 쓰는 행위(dual-write)**인데, Kafka는 `@Transactional`에 참여하지 않으므로 `send`는 커밋 전에 그 자리에서 바로 나간다. 그래서 둘이 깨질 수 있다:
- **send 성공 → 직후 DB 롤백**: 결제 명령은 Kafka에 떴는데 주문 row는 없다 → payment가 유령 주문을 결제(**유령 결제**). TS-3에서 본 그 현상의 근본 원인.
- **DB 커밋 → send 실패(브로커 다운)**: 주문은 PENDING으로 저장됐는데 명령이 안 나가 **사가가 시작도 못 함(발행 누락)**.

이 구멍은 `createOrder`뿐 아니라 `onPaymentReply`(→재고 명령), `onStockReply`(→환불 명령) 등 **모든 발행 지점**에 똑같이 있었다(전부 `SagaCommandPublisher`를 거친다).

### 핵심 개념 — Outbox = "발행"을 "같은 DB로의 INSERT"로 바꾼다
- 두 시스템(DB+Kafka)을 하나의 트랜잭션으로 묶는 건 불가능하다. 그래서 발상을 바꾼다: **Kafka로 바로 쏘는 대신, "이걸 나중에 발행하라"는 행을 *같은 DB*의 outbox 테이블에 적는다.**
- 주문 row 변경과 outbox INSERT는 **같은 로컬 트랜잭션** → 커밋되면 둘 다, 롤백되면 둘 다. 유령 결제도 발행 누락도 원천 차단.
- 실제 Kafka 발행은 **별도 릴레이(poller)**가 outbox의 PENDING 행을 주기적으로 읽어 발행하고 SENT로 마킹한다.
- **at-least-once는 사라지지 않고 오히려 릴레이가 만든다**: 발행 성공 후 markSent 커밋 전에 죽으면 다음 폴링에 또 보낸다. 이 중복은 **4d inbox(messageId)가 흡수** → **outbox(발행 보장) + inbox(중복 흡수)가 신뢰성 메시징의 짝**이고, 둘을 합쳐야 "정확히 한 번 효과"가 완성된다.

### 설계 결정
- **order 한 서비스에만 먼저 적용.** order의 세 발행(`createOrder`/`onPaymentReply`/`onStockReply`)이 전부 `SagaCommandPublisher` 한 곳을 거치므로, 이 클래스만 outbox로 바꾸면 세 발행이 한 번에 트랜잭셔널해짐 
- **payload는 직렬화된 JSON 문자열로 저장.** 릴레이는 이 문자열을 **`StringSerializer` 전용 `KafkaTemplate`**로 "있는 그대로" 발행한다. 기존 자동구성 템플릿은 `JsonSerializer`라 JSON 문자열을 *또* 인코딩해(따옴표로 감싸) 컨슈머가 못 읽는다. producer가 `add.type.headers=false`라 컨슈머는 타입 헤더 없이 fixed type으로 파싱 → 발행 바이트가 직접발행 시절과 동일.
- **동기 발행(`.get()`) 후 markSent.** 브로커 도착을 확인한 뒤에만 SENT로 마킹한다. 실패하면 예외 → 트랜잭션 롤백 → 그 행은 PENDING으로 남아 다음 폴링에서 재시도("보냈다고 거짓 마킹 후 유실"을 방지).
- **markSent는 dirty checking**으로 같은 `@Transactional` 안에서 UPDATE된다(별도 save 호출 없음).
- **폴링 방식(`@Scheduled(fixedDelay=1s)`).** 빈 폴링도 매번 DB를 때리는 비용이 있지만, CDC(Debezium 등)는 학습 범위 밖이라 폴링이 정답.

### 무엇을 만들었나 
- **1 · outbox 테이블**: `OutboxMessage`(`id` UUID PK len36 + `topic` + `message_key` + `payload` TEXT + `status` PENDING/SENT + `created_at`/`sent_at`) + `OutboxStatus` enum + `OutboxMessageRepository`(`findTop100ByStatusOrderByCreatedAtAsc`). 정적 팩토리 `create`, 도메인 메서드 `markSent`
- **2 · 발행 → 적재 전환**: `SagaCommandPublisher`에서 `KafkaTemplate` 의존 제거 → `OutboxMessageRepository` + `ObjectMapper`. 세 `send*`가 command를 JSON 직렬화해 outbox에 INSERT(호출부의 `@Transactional`에 참여). 메서드 시그니처 유지 → 오케스트레이터·테스트 무변경
- **3 · 릴레이**: `OutboxRelay`(`@Scheduled`, PENDING 묶음 읽어 동기 발행 후 `markSent`) + `KafkaProducerConfig`(`outboxKafkaTemplate`, String 직렬화) + `OrderApplication`에 `@EnableScheduling`
- **4 · 통합 관찰**: docker로 정상 흐름 + 강제 롤백 두 시나리오 확인

### 직접 관찰한 것
**환경**: `down -v`로 초기화 후 전체 기동. 상품 등록(키보드 30000/재고 100, productId=1).

#### A. 정상 주문 → outbox 경유 CONFIRMED (productId=1, 2개, amount 6만)
주문 생성 응답은 `status:"PENDING"`(사가 비동기 진행 중), 5초 뒤 조회 → `CONFIRMED`. outbox 테이블:
```
+--------------------------------------+------------------+--------+----------------------------+
| id                                   | topic            | status | sent_at                    |
+--------------------------------------+------------------+--------+----------------------------+
| d18f2db3-…-d031554244b0              | payment-commands | SENT   | 2026-06-17 18:03:21.967943 |
| b3ab6dc9-…-07766a16a36f              | stock-commands   | SENT   | 2026-06-17 18:03:22.988270 |
+--------------------------------------+------------------+--------+----------------------------+
```
→ 두 명령이 outbox에 적재됐다가 릴레이가 발행하며 **모두 `SENT`로 전환**(`sent_at` 채워짐). 재고 100→98. 발행이 더 이상 비즈니스 코드에서 직접 안 나가고 **DB(outbox) → 릴레이 → Kafka** 경로로 흐른다.

#### B. 강제 롤백 → 유령 결제 없음 (dual-write 해소의 결정적 증거)
`createOrder`에 발행(적재) 직후 강제 예외(`throw new RuntimeException(...)`)를 임시로 넣고 order-service만 재빌드 → 새 주문 시도(orderId=2):

```
[order] 사가 시작 -> 결제 명령 결정 orderId=2, amount=60000
[order] ProcessPayment 명령 outbox 적재 -> topic=payment-commands, orderId=2, amount=60000
ERROR ... java.lang.RuntimeException: [임시] dual-write 관찰용 강제 롤백
    at com.commerce.order.service.OrderService.createOrder(OrderService.java:35)
```
요청은 500. 롤백 후 상태:

| 확인 | 롤백 전 | 롤백 후 |
|---|---|---|
| `orders` | id=1 (CONFIRMED) | **id=1만** (orderId=2 row 없음) |
| `COUNT(outbox_messages)` | 2 | **2** (orderId=2 적재가 무효화) |
| `payments` | order_id=1 (APPROVED) | **order_id=1만** (유령 결제 없음) |

**결정적 포인트**: 로그상 "ProcessPayment 명령 outbox 적재" INFO는 찍혔지만 `outbox_cnt`는 안 늘었다. 로그는 트랜잭션 커밋 *전* 메모리 상태고, INSERT는 롤백으로 무효화됐다 — **"로그상 적재됐어도 커밋 안 되면 없던 일."** 예전 직접발행이었다면 저 시점에 이미 `send`가 Kafka로 나가 payment가 orderId=2를 결제했을 것이고, 주문은 롤백돼 없는데 결제만 떠도는 불일치가 남았을 것이다. outbox가 해당 단점을 보완 

### 배운 것
- **dual-write는 "두 시스템을 한 트랜잭션으로 못 묶어서" 생기는 구조적 문제다.** outbox의 본질은 "발행"이라는 외부 행위를 "같은 DB로의 INSERT"라는 로컬 행위로 바꿔 트랜잭션 안으로 끌어들이는 것
- **outbox는 at-least-once를 없애지 않는다 — 만든다.** 릴레이의 발행/마킹 사이 틈 때문에 재발행이 생긴다. 그래서 4d inbox 없이는 outbox만으로 불완전하다. 둘은 짝(저장·발행의 원자성 ↔ 중복 흡수)
- **커밋되지 않은 부수효과는 존재하지 않는다.** 관찰 B에서 로그는 찍혔지만 행은 없었다 — 트랜잭션 원자성을 눈으로 본 사례. "코드가 실행됐다 ≠ 영속됐다"
- **직렬화 형식이 발행 경로를 바꾼다.** 이미 JSON인 payload를 JsonSerializer로 또 감싸면 깨진다 → 릴레이엔 StringSerializer 전용 템플릿이 필요. "무엇을 직렬화된 상태로 저장하느냐"가 발행단 설계에 직결
- **신뢰성에는 공짜가 없다.** outbox는 정합성을 사지만 폴링 비용(빈 SELECT 반복)과 발행 지연(최대 폴링 주기)을 낸다. 더 줄이려면 CDC로 가야 하고, 그건 또 다른 인프라 부담

---

## Step 5a — API Gateway (단일 진입점 + 경로 라우팅 + 횡단 로깅)

### 직전의 고통
4단계까지 진입점이 **포트로 흩어져** 있었다 — 주문 8080, 상품 8081, 결제 8082. 클라이언트가 "어느 기능이 어느 포트"라는 **서비스 토폴로지를 직접 알아야** 했고, 로깅·인증 같은 횡단 관심사를 넣으려면 서비스마다 반복해야 했다. 게다가 곧 할 분산추적(5b)·서킷브레이커(5c)가 올라탈 **공통 진입점**이 없었다.

### 핵심 개념 — Gateway = 단일 진입점 + 경로 기반 라우팅
- 클라이언트는 게이트웨이(`:8000`) 하나만 안다. 게이트웨이가 경로 prefix로 뒤의 서비스를 골라 프록시한다.
- 뒤 서비스의 호스트/포트가 바뀌어도 클라이언트는 무영향(게이트웨이 라우트만 수정). 토폴로지가 클라이언트에서 분리된다.
- 모든 트래픽이 한 곳을 지나므로 **횡단 관심사(로깅/추적/인증/rate-limit)를 게이트웨이에서 한 번만** 처리한다.

### 설계 결정
- **Spring Cloud Gateway(2025.0.0, server-webflux).** Netty 기반 reactive 라우터. 2025.0.0에서 스타터/프로퍼티명이 바뀐 점 반영: `spring-cloud-starter-gateway-server-webflux`, route prefix `spring.cloud.gateway.server.webflux.routes`(구 `spring-cloud-starter-gateway`/`spring.cloud.gateway.routes`는 deprecated).
- **경로는 다운스트림과 동일**(`/api/v1/{orders,products,payments}`) → `StripPrefix` 불필요.
- **라우팅 대상도 DNS(서비스 이름)으로**(고정 IP 금지 습관 유지).
- **stateless** — 게이트웨이는 자기 DB/Kafka가 없다.
- **백엔드 health를 기다리지 않는다**(`depends_on` 비움). order가 product를 안 기다린 것과 같은 철학 → 백엔드 부재가 런타임 에러로 드러나고, 그 격리/폴백은 **5c(서킷브레이커)**에서 다룬다.

### 무엇을 만들었나 
- **1. 스캐폴드 + 라우팅**: `gateway-service` 모듈 + `application.yml`에 라우트 3개 + compose에 추가 + `GATEWAY_PORT`. `/actuator/gateway/routes`로 등록 라우트 조회.
- **2. 횡단 로깅**: `RequestLoggingGlobalFilter`(`GlobalFilter`, HIGHEST_PRECEDENCE) — 전 요청을 게이트웨이 한 곳에 `[gateway] method path -> route status (ms)`로 기록.

### 직접 관찰한 것
- 게이트웨이 한 포트(`:8000`)로 `GET /api/v1/products/1`, `POST /api/v1/orders` 둘 다 닿음(각각 product/order로 라우팅) → **8080/8081을 직접 몰라도 됨**.
- 모든 요청이 게이트웨이 로그 한 곳에 한 줄로 찍힘.
- **막힌 것(TS-5)**: `/actuator/gateway/routes`가 404. `exposure.include`로 노출만 해선 부족하고 `management.endpoint.gateway.access=unrestricted`로 **access까지 열어야** 200(Boot 3.5에서 `enabled` deprecated→`access`).

### 배운 것
- **게이트웨이의 본질은 "토폴로지를 클라이언트에서 떼어내는 것".** 진입점을 모으면 그 뒤 구조를 자유롭게 바꿀 수 있다.
- **단일 진입점은 횡단 관심사의 자리를 만든다.** 조각2 로깅 필터가 5b trace id가 올라탈 받침대가 됐다.
- **actuator는 노출(`exposure`) ≠ 접근(`access`).** 운영 정보를 드러내는 엔드포인트는 두 관문을 다 통과해야 보인다.

---

## Step 5b — 분산추적 (Micrometer Tracing + Zipkin / HTTP·Kafka·Outbox 전 구간)

### 직전의 고통
4단계 내내 사가(주문→결제→재고, 실패 시 환불)를 쫓을 때 `docker compose logs`를 서비스별로 열고 **`orderId`로 grep해서 머릿속으로 이어 붙였다.** 게이트웨이까지 들어온 뒤엔 한 요청이 gateway→order(HTTP) + order→payment→product(Kafka)로 더 흩어지는데, 이들을 **하나로 묶는 공통 끈이 없었다.** 비즈니스 키(`orderId`)가 안 찍힌 로그(게이트웨이 라우팅, 직렬화 에러)는 아예 못 이었다.

### 핵심 개념 — Trace/Span + Context Propagation
- **Trace**: 한 요청이 시스템 전체를 지난 여정. **TraceId** 하나로 식별. **Span**: 그 안의 작업 단위(부모-자식 트리 = 워터폴).
- **전파(propagation)**: 호출하는 쪽이 trace context를 **실어 보내고** 받는 쪽이 **이어받는다**. HTTP는 헤더(W3C `traceparent`/B3), Kafka는 **메시지 헤더**.
- 스택: **Micrometer Tracing**(Sleuth 후継) + **Brave 브리지** + **Zipkin 리포터/UI**. 학습용이라 샘플링 100%.

### 설계 결정
- **자동 계측이 닿는 곳은 프로퍼티로, 안 닿는 곳은 코드로.** HTTP(gateway↔order)는 자동. Kafka는 observation을 켜야 하는데, **커스텀 컨테이너 팩토리/KafkaTemplate(TS-4의 타입별 팩토리)에는 프로퍼티가 적용되지 않아** 코드로 직접(`setObservationEnabled(true)`).
- **Zipkin은 best-effort.** 어느 서비스도 zipkin에 `depends_on` 안 함(추적 인프라가 죽어도 서비스는 떠야 한다). in-memory 저장(학습용).

### 무엇을 만들었나 
- **1. 추적 기반 + HTTP + Zipkin**: 4개 서비스에 tracing 의존성/설정 + compose에 Zipkin. → gateway→order HTTP가 한 trace, 로그에 `[traceId,spanId]`
- **2. Kafka 전파**: order/payment 커스텀 팩토리·템플릿에 observation 코드로 활성화 + 3서비스 프로퍼티
- **3. Outbox 경계 잇기**: outbox 행에 trace context 저장(`SagaCommandPublisher`) → 릴레이가 복원해 발행(`OutboxRelay`)
- **+ 게이트웨이 로그 fix**: reactive라 traceId가 안 박히던 문제(TS-6) 해결

### 막힌 것 / 새 개념
- **TS-6 — reactive 게이트웨이 로그의 traceId 빈칸**: trace context는 Mono **구독 시점**에만 ThreadLocal에 있어, 조립 시점에 읽으면 null. 콜백 안에서 읽기 + `Hooks.enableAutomaticContextPropagation()`로 해결.
- **TS-7 — Outbox가 trace를 끊는다**: observation을 켜도 사가가 3조각으로 분리됐다. 릴레이가 **다른 스레드/나중에** 발행해 원 요청의 trace를 잃기 때문. **trace context를 메시지와 함께 저장→복원**해야 이어진다. (멱등 키 `messageId`를 행에 저장한 4d와 같은 발상)

### 직접 관찰한 것
**성공 사가** (정상 주문) → Zipkin에서 **한 trace, 13 span, 4개 서비스**:
```
gateway POST → order POST → outbox-relay.publish → payment-commands send → payment receive
            → payment-replies send → order receive → outbox-relay.publish → stock-commands send
            → product receive → stock-replies send → order receive
```
**실패→환불 보상 사가** (결제 승인되나 재고 부족) → **한 trace, 16 span**, 보상 구간까지 포함:
```
... → product(stock FAILED) → order receive → outbox-relay.publish
    → payment-refund-commands send → payment receive(환불)
```
→ 게이트웨이가 로그에 찍은 그 traceId가 Zipkin trace와 동일. **`grep <traceId>` 하나로 그 요청의 전 구간 로그가 모인다.**

### 배운 것
- **분산추적의 진짜 적은 비동기 경계다.** HTTP·동기 Kafka는 observation만 켜면 전파되지만, **Outbox/큐/스케줄러 같은 store-and-forward는 trace를 떨군다** → 컨텍스트를 데이터와 함께 저장·복원해야 한다
- **reactive에선 context가 ThreadLocal이 아니라 Reactor Context에 산다.** servlet 감각으로 "현재 span 읽기"를 하면 null. 콜백 안에서 + 자동 전파를 켜야 한다
- **`Propagator` 추상화로 포맷 의존을 피한다.** traceparent를 손으로 만들지 말고 inject/extract를 쓰면 B3/W3C 어느 포맷이든 대칭
- **trace는 grep의 상위호환.** 비즈니스 키로 잇던 것을 시스템이 발급한 id 하나로, 게다가 워터폴(어디서 몇 ms)까지 본다

---

## Step 5c — Circuit Breaker (Resilience4j, 게이트웨이 다운스트림 격리)

### 직전의 고통
5a에서 게이트웨이가 백엔드 health를 일부러 안 기다리게 두고("5c에서 다룬다") 백엔드를 안 띄우니 **raw 500**(NXDOMAIN)이 나왔다. 그대로 두면: 백엔드가 죽거나 느릴 때 게이트웨이는 **타임아웃까지 기다렸다 실패**(fail-slow)하고, 요청이 쌓이면 스레드/커넥션이 묶여 **멀쩡한 다른 라우트까지 느려진다(장애 전파)**. 죽은 서비스를 계속 때려 회복도 방해하고, 사용자에겐 그냥 500(우아한 저하 없음).

### 핵심 개념 — 회로 차단기 + 상태머신
- 다운스트림 호출을 감싸 실패율을 보다가, 임계치를 넘으면 **회로를 열어** 호출을 막고 즉시 폴백.
- **CLOSED**(정상, 실패 집계) → **OPEN**(차단, 즉시 폴백, fail-fast) → **HALF_OPEN**(대기시간 후 시험 호출) → 성공하면 CLOSED / 실패하면 OPEN.
- **어디에 거나**: Circuit Breaker는 **동기 호출**을 위한 도구다. 우리 서비스 간 사가는 전부 **Kafka(비동기)**라 대상이 아니다(컨슈머가 죽어도 메시지는 큐에 쌓일 뿐 호출 스레드가 막히지 않는다). **남은 동기 호출 = 게이트웨이→백엔드(HTTP)** 한 곳 → 거기에만 건다.

### 설계 결정
- **Spring Cloud Gateway + Resilience4j(reactor) 통합.** 라우트에 `CircuitBreaker` 필터 + `fallbackUri`를 붙이면, 실패/OPEN 시 게이트웨이가 폴백 경로로 forward한다.
- **라우트마다 독립 인스턴스**(order/product/payment 각각). product가 죽어 그 회로가 OPEN돼도 order/payment 회로는 영향 없음 = **의존성별 격리**. 공통값은 `configs.default` + `base-config`로 상속(중복 제거).
- **폴백은 게이트웨이 안의 컨트롤러**(`/fallback/{서비스}`)가 503 + 친절한 본문 반환.
- 회로 상태는 actuator(`/actuator/circuitbreakers`, `/health`)로 노출 → 전이를 눈으로.

### 무엇을 만들었나 (조각별)
- **조각 1**: `spring-cloud-starter-circuitbreaker-reactor-resilience4j` + **product 라우트** CircuitBreaker 필터 + `FallbackController`(503) + resilience4j 설정(window10/최소5/실패율50%/OPEN 10s/half-open 3) + actuator.
- **조각 2**: **order·payment 라우트로 확대**(독립 인스턴스 3개), 폴백 컨트롤러에 endpoint 추가, 설정을 `configs.default` 상속 구조로 리팩터.

### 직접 관찰한 것
**상태머신 한 바퀴 (product 라우트)**: product 정상 → 200/CLOSED. `docker compose stop product-service` 후 호출 → 모두 **폴백 503**, 실패 누적되자 **CLOSED→OPEN**. OPEN 중엔 product를 **안 부르고 즉시 폴백**(fail-fast). product 재기동 + 10s 후 **HALF_OPEN** 자동 전이 → 시험 호출 성공 → **CLOSED** 복귀(200).
```
정상:   200  state=CLOSED
다운:   503(폴백) ... → state=OPEN  (이후 product 호출 안 함)
복구:   200  state=HALF_OPEN → HALF_OPEN → CLOSED
```
**의존성별 격리**: product만 죽이고 그 라우트를 6번 때려 `productCircuitBreaker`만 OPEN으로 만든 뒤, order/payment를 호출 → 두 회로는 **CLOSED 유지**. 한 다운스트림의 장애가 다른 회로로 번지지 않음.

### 배운 것
- **Circuit Breaker는 동기 호출의 도구다.** 비동기(Kafka)에는 안 쓴다 — 큐가 이미 시간 결합을 끊어줬기 때문. "어디가 동기 경계인가"를 알면 어디에 걸지가 정해진다.
- **fail-fast가 핵심 가치.** 죽은 서비스를 기다리지 않고 즉시 폴백 → 게이트웨이 자원을 지키고(다른 라우트 보호) 죽은 서비스의 회복을 방해하지 않는다.
- **격리는 "인스턴스 분리"로 산다.** 회로를 의존성별로 나눠야 한 서비스 장애가 전체로 안 번진다(bulkhead의 사상).
- **우아한 저하 ≠ 성공.** 폴백 503은 "실패를 사용자에게 친절하게 알리는 것"이지 요청을 성공시키는 게 아니다. 무엇을 폴백으로 줄지(에러/캐시/기본값)는 도메인 판단.

---

## Step 6 — 모니터링 (Prometheus + Grafana + Loki, observability 삼각형)

> Phase 1 로드맵(1~5단계)은 5c로 완주했다. Step 6부터는 **확장 트랙(Phase 1.5)**. 순서는 6(측정) → 7(Kafka 성능) → 8(쿠버네티스)

### 직전의 고통
5b에서 trace(Zipkin)를, 액추에이터로 메트릭(`/actuator/prometheus`)을 노출은 했지만 — **관측 데이터가 세 군데로 흩어져** 있었다. 메트릭은 `curl`로 그 순간 스냅샷만, 로그는 `docker compose logs`로 터미널에 흩어지고, 트레이스는 Zipkin에 따로. 장애가 나면 "어디가 이상한지(메트릭) → 무슨 일이 났는지(로그) → 그 요청의 전체 경로(트레이스)"를 **손으로 세 도구를 오가며** 이어붙여야 했다. 

### 핵심 개념 — observability 삼각형 (metrics / logs / traces)
- **메트릭**: 숫자 시계열(요청량·에러율·지연·JVM). "**무언가 이상하다**"를 빨리 알아챈다
- **로그**: 이벤트 텍스트. "**무슨 일이 났는지**"를 구체적으로 본다
- **트레이스**: 한 요청의 서비스 간 경로/지연. "**어디서 느려졌/깨졌는지**"를 본다
- 셋은 **서로를 보완**한다. 셋을 연결(메트릭→로그→트레이스)해야 분석이 한 흐름이 된다

### 설계 결정
- **수집은 pull, 코드는 무변경** Prometheus가 각 서비스의 `/actuator/prometheus`를 **긁어온다(pull)**. Zipkin이 span을 **받던(push)** 것과 방향이 반대. pull이라 **대상이 죽으면 `up=0`으로 그 사실 자체가 메트릭이 된다.** 서비스 코드는 한 줄도 안 바꿨다 — 이미 노출 중인 엔드포인트를 긁기만.
- **전부 코드로 프로비저닝** Grafana datasource·대시보드를 **파일로** 박았다. 클릭 설정은 `down -v` 한 번에 사라지지만, 파일 프로비저닝은 **다시 올려도 동일하게 재현**된다(GitOps식). 대시보드는 Prometheus가 보장하는 **`job` 라벨**로 키를 잡아 서비스 추가에도 안 깨지게.
- **지연 SLI를 위해 히스토그램을 켰다** 기본 `http.server.requests`는 count/sum/max만이라 **p95/p99를 못 구한다.** `percentiles-histogram` 을 켜 `_bucket` 시계열을 노출 → Grafana가 `histogram_quantile()`로 백분위 계산. (서비스당 application.yml 한 블록, Java 무변경)
- **로그는 push, 트레이스와 연결** Loki("로그용 Prometheus") + Promtail. Promtail이 **도커 소켓**으로 모든 컨테이너 stdout을 tail해 Loki로 **push**(다시 pull과 대비). 핵심은 **derived field** — 5b가 로그에 박아둔 `[service,traceId,spanId]`에서 traceId를 정규식으로 뽑아 **Zipkin 트레이스로 점프하는 링크**를 만들었다. 메트릭(Grafana)→로그(Loki)→트레이스(Zipkin)가 **한 화면에서 연결**된다.

### 무엇을 만들었나 
- **1. Prometheus**: `monitoring/prometheus/prometheus.yml`(4개 서비스+자기 자신 scrape) + compose에 `prometheus`(depends_on 없음 = best-effort)
- **2. Grafana**: datasource 프로비저닝(Prometheus, uid 고정) + 대시보드 provider + 대시보드 2종(Service Overview: up/요청량/에러율/p95·p99/CPU, JVM: 힙/논힙/GC/스레드/클래스) + 4개 서비스에 `percentiles-histogram` 활성화
- **3. Loki+Promtail**: Loki 단일 바이너리 + Promtail(docker_sd로 컨테이너 로그 수집, `service` 라벨) + Grafana에 Loki(derivedField→Zipkin)·Zipkin 데이터소스 추가 + Logs 대시보드(로그량 그래프 + 로그 패널)

### 직접 관찰한 것
- **pull이 죽음을 잡는 순간**: `up` 쿼리로 4개 서비스 1 확인 → `docker compose stop product-service` → 15초(scrape 주기) 뒤 **product-service만 `up=0`**. 모니터링이 "죽었다"를 자동 인지
- **지연 백분위가 그려짐**: 트래픽을 흘리니 Overview의 **p95/p99 패널이 채워짐** = 히스토그램 버킷이 실제로 노출됐다는 증거(`http_server_requests_seconds_bucket` 존재)
- **재현성**: `docker compose down -v && up` 후에도 **3개 데이터소스 + 4개 대시보드가 그대로** 복원. 클릭이 아니라 코드라서
- **삼각형 연결**: Loki 로그 한 줄을 펼쳐 **`TraceID` 링크 클릭 → Grafana 안에서 Zipkin 트레이스 워터폴**로 점프

### 막힌 것 → 트러블슈팅
- **TS-8**: 조각3에서 `datasource.yml`에 Loki를 추가했는데 대시보드에 `Datasource loki was not found`. 원인은 **Grafana는 프로비저닝을 부팅 시점에 한 번만 읽는데, 기존 grafana 컨테이너가 재생성되지 않아**(compose 출력이 `Recreated`가 아니라 `Running`) 새 설정을 못 읽은 것. `docker compose restart grafana`로 해결. **"설정 파일 교체 ≠ 프로세스 재적재"** 

### 배운 것
- **측정이 튜닝의 전제다.** "빨라졌다"는 숫자로 증명해야 한다 — 이 토대(6)가 있어야 7단계 Kafka 튜닝의 전후 비교가 가능하다.
- **pull vs push는 트레이드오프다.** pull(Prometheus)은 대상 죽음을 자동 감지(`up`)하지만 대상 목록을 알아야 한다. push(Zipkin/Promtail)는 대상을 몰라도 되지만 죽음은 "안 들어옴"으로만 안다.
- **observability는 코드여야 산다.** 클릭으로 만든 대시보드는 휘발된다. 프로비저닝으로 박아야 재현·리뷰·버전관리가 된다.
- **세 신호의 가치는 연결에서 나온다.** 메트릭·로그·트레이스를 각각 가진 것보다, traceId로 셋을 잇는 한 줄(derived field)이 분석 흐름을 바꾼다.

---

## Step 7 — Kafka 부하테스트 · 성능 (진행중)

> 확장 트랙(Phase 1.5)의 둘째. Step 6의 측정 토대 위에서 **"부하를 줘 병목을 만들고, 설정으로 잡는다".** 조각: ① lag 관측 → ② 부하·병목 재현 → ③ 파티션↑ → ④ concurrency → ⑤ producer 튜닝.

### 직전의 고통
지금까지 사가는 **"한 건이 잘 도는지(기능 정합성)"** 만 봤지, **부하 하에서 어디가 먼저 무너지는지(병목)** 는 한 번도 안 봤다. Step 6에서 메트릭·로그·트레이스을 달았지만 평상시 트래픽이라 그래프가 평평했다 

### 핵심 개념 — consumer lag
`lag = (토픽 끝 offset, LEO) − (컨슈머가 커밋한 offset)` = **"아직 처리 못 한 메시지 수"**.
- `lag 0` = 들어오는 족족 처리(여유). **`lag` 우상향 = 유입 > 처리 = 병목.**
- 부하테스트의 목표 = **"우상향하는 lag을 평평/0으로 만드는 설정을 찾는 것"**. 

### 설계 결정
- **lag을 보는 "눈"부터(조각1).** 브로커는 lag을 Prometheus 형태로 안 내보낸다 → `kafka-exporter`(사이드카)가 offset을 물어 `kafka_consumergroup_lag` 등으로 변환. Grafana 신규 대시보드(`commerce-kafka`)로 lag·파티션·유입율·처리율을 시각화. **부하 주기 전에 눈부터.**
- **부하는 k6, 고정 RPS(조각2).** `constant-arrival-rate`로 "초당 N건"을 직접 통제 → 유입속도와 처리속도의 격차를 패널에서 1:1로 본다. order 직접(:8080)으로 변수 최소화.
- **병목을 "재현"해야 측정할 게 생긴다.** 1차 부하(200RPS)에선 lag이 안 쌓였다 — 우리 결제가 **비현실적으로 빨라서**(1ms 미만). 현실의 결제(외부 PG 승인)는 수십~수백 ms. 그래서 payment 컨슈머에 **환경변수로 제어하는 인위적 처리지연**(`ProcessingDelay`, 기본 0)을 주입해 현실을 모사 → 단일 컨슈머의 처리 천장을 낮춰 병목을 재현.

### 무엇을 만들었나
- **조각1**: `kafka-exporter`(compose, danielqsj v1.8.0, :9308) + `prometheus.yml` scrape job + Grafana `commerce-kafka.json`(lag/파티션/유입·처리율 5패널)
- **조각2**: k6 부하스크립트 `loadtest/scripts/order-load.js`(고정 RPS, `-e RATE/DURATION`) + payment `ProcessingDelay`(env `PAYMENT_PROCESSING_DELAY_MS`, 기본 0=무영향)

### 직접 관찰한 것 
- **1차 (지연 0, 200 RPS) — 병목 미발생.** lag이 최대 45까지 찰랑이다 부하 끝나니 **0 복귀**. 처리 ≥ 유입, p95 7ms. = "이 부하론 여유" + **"병목 관찰엔 처리비용이 부족하다"** 는 진단.
- **2차 (지연 50ms, 50 RPS) — 병목 재현 성공.** 처리 천장 `= 1000ms / 50ms = 20/s`, 유입 `50/s` → 매초 **+30 누적**. `payment-commands` lag이 **언덕 모양으로 우상향, 최대 ≈ 3.13k**. 부하 종료 후 20/s씩 천천히 감소(밀린 백로그 소화). order/product lag은 거의 0 (payment가 throttle 지점).
- **주문 접수는 내내 빨랐다(p95 5ms).** 병목은 동기 응답이 아니라 **비동기 컨슈머 뒤에 숨는다** — lag 관측이 없었으면 "겉보기 멀쩡"으로 놓쳤을 것.

### 메시지 키 = orderId (파티션 안전성의 근거)
조각3(파티션↑) 착수 전 확인: `payment-commands`는 **`orderId`를 키로 발행**된다(`SagaCommandPublisher` → Outbox → `OutboxRelay`까지 키 보존). 파티션을 늘려도 `hash(orderId) % N`으로 **같은 주문은 항상 같은 파티션 → 키 단위 순서 보장 → 사가 안전**.

### 배운 것 (조각1·2 시점)
- **병목은 "만들어야" 보인다.** 정상 동작하는 시스템에 부하만 준다고 병목이 나오지 않는다. **처리 비용이 현실적이어야(또는 부하가 충분히 커야)** 천장이 드러난다.
- **비동기는 병목을 숨긴다.** 동기였으면 p95·503으로 즉시 드러날 부하가, 비동기에선 컨슈머 lag으로 **뒤에** 쌓인다. 그래서 lag 관측이 필수.
- **lag의 기울기 = 유입 − 처리.** 천장(처리율)을 숫자로 알면 어느 부하에서 무너질지 예측되고, 튜닝 목표(**천장을 유입 위로 올리기**)가 명확해진다.

### 조각3 — 파티션↑ 
- **무엇을**: `payment-commands`의 파티션 수를 env로 변수화. order `KafkaTopicConfig`(`PAYMENT_COMMANDS_PARTITIONS`, 기본 1=평소 무영향)만 변수, 나머지 토픽은 1 유지. `.env`에서 **4로 켜고** 재기동 → KafkaAdmin이 기동 시 파티션을 1→4로 자동 증가(증가만 가능, 감소 불가)
- **왜 파티션인가**: 파티션 = 토픽을 쪼갠 **병렬 처리 단위 = "일꾼이 앉을 자리 수"**. 1개면 같은 컨슈머 그룹에서 **일꾼(스레드)도 무조건 1명**. 그래서 조각2의 천장 20/s가 고정됐던 것. 자리를 4개로 늘려 일꾼 4명이 붙을 *여지*를 만든다
- **직접 관찰 — lag 여전히 ≈ 4.09k 우상향(함정 확인).** 파티션만 4로 늘리고 payment `@KafkaListener` **concurrency=1**이면 → **1스레드가 4파티션을 혼자 다 읽어** 결국 50ms씩 순차 처리. 천장 20/s 그대로 → lag 안 줄어듦. **"자리만 늘리고 일꾼은 그대로"** 를 눈으로 확인. 이게 조각4의 동기

### 조각4 — concurrency로 천장 돌파
- **무엇을**: payment `KafkaConsumerConfig`의 **process 팩토리에만** `factory.setConcurrency(...)` 주입(env `PAYMENT_COMMANDS_CONCURRENCY`, 기본 1). 환불 팩토리는 1 유지. `.env`에서 **4로**
- **핵심 규칙**: **한 파티션 = 한 컨슈머 스레드**(같은 그룹 내). concurrency는 파티션 수와 **1:1로** 맞춰야 의미. 스레드 > 파티션이면 초과분은 **유휴**(파티션을 못 받음)
- **직접 관찰 — 병목 해소.** 처리 천장 `= 1000ms / 50ms × 4스레드 = 80/s` > 유입 50/s. lag이 **언덕(≈4k) → 최대 45, 평탄**(사실상 0). 부하 중에도 안 쌓인다
- **로그로 못 박음**: 파티션 배정이 **4개 스레드(`...ntainer#0-0-C-1`~`#0-3-C-1`)에 `payment-commands-0~3` 1:1**로 나뉘어 찍힘. 조각3에선 한 스레드가 `[-0,-1,-2,-3]`을 다 들고 있었다. 환불 토픽은 의도대로 1스레드 유지

### 배운 것 (조각3·4 시점)
- **파티션은 "병렬의 상한", concurrency는 "실제 일꾼 수". 둘 다 올려야 효과.** 파티션만↑(조각3)은 자리만 늘린 빈 의자 — 처리량 0 변화. 둘을 1:1로 맞췄을 때(조각4) 비로소 천장이 4배로
- **튜닝 목표를 숫자로**: 천장 = `1000ms / 처리시간 × 스레드`. 유입 위로 올리면 lag이 평탄. baseline(20/s)→해소(80/s)가 그래프로 깔끔히 보였다
- **순서 안전은 키가 지킨다(조각3 사전확인의 회수).** 파티션 4개로 흩어져도 `hash(orderId)`로 같은 주문은 같은 파티션 → 키 단위 순서 보장. 그래서 병렬화가 사가를 안 깬다

### 조각5 — producer 튜닝 (linger.ms / batch.size / compression / acks)
- **무엇을**: 두 가지. `KafkaProducerConfig`에 `acks`/`linger.ms`/`batch.size`/`compression.type` 4종을 env화(`outbox.producer.*`, 기본=Kafka 기본값=무영향), **`OutboxRelay`를 "한 건 `send()` → 즉시 `.get()`으로 ack 대기" 반복에서 → "PENDING 100건 전부 비동기 `send()` → `flush()` 한 번 → 도착 확인 후 `markSent`" 배치로 리팩터.** 동기 .get()-per-message 구조에선 producer 버퍼에 한 번에 한 건뿐이라 `batch.size`/`linger.ms`가 묶을 게 없다 — **배치를 살리려면 설정 이전에 발행 패턴부터 비동기 다발 → flush여야 한다**
- **계측 함정(→ TS-9)**: 부하 주기 전 `actuator/prometheus`에 `kafka_producer_*` native metric이 **하나도 안 나왔다**. 원인은 우리가 `DefaultKafkaProducerFactory`를 **직접 `new`** 해서 — Spring Boot가 자동 구성하는 factory엔 `KafkaClientMetrics`가 자동으로 붙지만, 커스텀 factory엔 안 붙는다. `producerFactory.addListener(new MicrometerProducerListener<>(meterRegistry))`를 직접 등록해 해결. **"커스텀 빈을 직접 만들면 자동 계측도 같이 잃는다"**
- **직접 관찰 — A/B 비교(같은 부하 50RPS·90s, payment delay=50ms)**:

  | 지표 | Run B baseline<br>(linger=0/batch=16K/none) | Run A 튜닝 ON<br>(linger=20/batch=64K/lz4) | 의미 |
  |---|---|---|---|
  | `records_per_request_avg` | 16.2 | **99.1** | 한 produce 요청에 묶이는 레코드 6배 ↑ |
  | `batch_size_avg` | 828 B | 2116 B | 배치 2.5배 ↑ |
  | `request_rate` | 3.71/s | **0.91/s** | produce 요청 횟수 ¼로 ↓(네트워크 왕복 절감) |
  | `compression_rate_avg` | 1.0 (압축 X) | **0.613** | lz4가 ~39% 압축 |
  | `record_send_total` | 9004 | 9004 | 동일 부하량(대조군 검증) |

- **반전 — 코드 추론을 실측이 정정했다.** 관찰 전엔 "릴레이가 `flush()`를 부르니 `linger.ms`는 무시되고 설정은 무용지물, 배치는 순전히 1초 폴링 구조가 만든다"고 추론했다. **틀렸다.** `linger=20`+`batch=64K`로 올리니 요청당 묶음이 16→99로 6배, 요청 횟수가 ¼이 됐다. 이유: `flush()`가 sender를 즉시 깨우는 건 맞지만, **100건을 for문으로 빠르게 `send()`하는 그 짧은 구간** 동안 `batch.size`가 작으면 배치가 일찍 꽉 차 잘게 쪼개져 나가고 `linger=0`이면 즉시 전송된다. batch가 크고 linger가 있으면 같은 100건이 **더 적은 수의 큰 배치**로 묶인다. **flush가 있어도 linger/batch.size는 "한 폴링 사이클 안의 묶음 단위"에 실제로 영향을 준다.**

### 배운 것 (조각5 시점)
- **배치는 발행 패턴이 먼저, 설정이 그 다음.** 동기 .get()-per-message였다면 버퍼에 한 건뿐이라 어떤 설정도 안 묶인다. 비동기 send→flush로 패턴을 바꾼 뒤에야 `linger`/`batch.size`가 묶음 단위를 키우는 레버로 작동했다
- **compression은 별도 축.** 묶음 수(`records_per_request`)와 무관하게 전송 바이트만 줄인다(lz4 → 0.61). 묶음↑(네트워크 왕복↓)과 압축(대역폭↓)은 다른 이득
- **정직한 한계 ①**: 이 producer 효율 개선은 **consumer lag을 줄이지 않는다.** 우리 병목은 consumer 처리(50ms)였고 조각4(concurrency)에서 이미 해소했다. producer 튜닝은 **발행측 효율**(왕복·대역폭) 개선이지 end-to-end 처리량 천장(=consumer)을 올리는 게 아니다 — 메커니즘 체득이 목적
- **정직한 한계 ②**: 단일 브로커라 `acks=all ≈ acks=1`(ISR=리더 자신뿐). 복제/내구성 트레이드오프는 못 봤다 — 인지만

### 진행 상태
조각1~5 완료 = **Step 7 마무리.** **병목 재현(≈4k 우상향) → 파티션만으론 미해소(함정) → concurrency 1:1로 천장 4배(lag 평탄) → producer 배치/압축 튜닝(요청당 묶음 6배·요청 ¼·압축 39%, A/B로 확인)** 까지 관찰. 핵심 루프(파티션 ↔ concurrency ↔ producer 배치)를 코드와 숫자로 다 돌았다. 다음: **Step 8 — 쿠버네티스(로컬 kind/k3d).**

---

## Step 8 — 쿠버네티스 (로컬 kind, 진행중)

### 직전의 고통
지금까지 전부 `docker-compose`였다. compose는 "한 파일에 서비스 나열 → `up`" 으로 충분했지만, **compose가 암묵적으로 가려주던 것들**(기동 순서, 의존 서비스 존재, 내부 DNS)이 k8s에선 명시적으로 드러난다 — 그 차이를 직접 겪어보기 (kind = 클러스터 노드 자체가 도커 컨테이너)

### 핵심 개념 — 선언형 매니페스트 + 리소스 kind 분리
- **매니페스트 = "원하는 상태"를 적은 YAML 선언서.** 명령형("띄워라")이 아니라 선언형("이 상태였으면"). `kubectl apply` → k8s가 현재 상태와 비교해 그 상태로 **수렴**시킨다(파드 죽으면 자동 재생성)
- compose 블록 하나 → k8s는 **리소스 kind별로 쪼개서** 번역한다:

  | k8s kind | compose 대응 | 역할 |
  |---|---|---|
  | `Deployment` | 앱 `service` 블록 | 상태 없는 앱. 죽으면 아무 노드서나 새로(고정 이름·디스크 불필요) |
  | `StatefulSet` | DB·kafka 블록 | 상태 있는 것. 고정 이름(`-0`) + 전용 디스크(PVC) |
  | `Service`(ClusterIP/headless) | compose 내부 DNS | 파드 앞 고정 주소/DNS, 로드밸런싱 |
  | `ConfigMap` / `Secret` | `environment:` 평문 / 비번 | 설정값 / 자격증명 분리 |
  | probe(startup/readiness/liveness) | `healthcheck` | actuator 헬스 → k8s probe로 전환|

### 설계 결정
- **앱 = Deployment, 상태(DB·kafka) = StatefulSet.** kafka는 "자기 자신을 가리키는 안정적 네트워크 정체성"(advertised 주소 + 컨트롤러 쿼럼 보터)이 필요해 랜덤 이름의 Deployment로는 불가 → 고정 이름 `kafka-0`
- **이미지 로드 전략이 둘로 갈린다**: product/order/payment는 **로컬 빌드** 이미지라 `kind load`로 노드 캐시에 넣어야 함(레지스트리에 없음). kafka는 **공개 이미지**(`apache/kafka:3.9.0`)라 노드가 Docker Hub에서 직접 pull → load 불필요
- **조각 순서를 kafka가 앱보다 앞에 오게 보정**(원래 뒤 조각이었음). 앱이 부팅 때 kafka DNS에 의존하기 때문 — 아래 관찰이 그 이유를 증명한다
- Step7 부하 노브(`PAYMENT_COMMANDS_PARTITIONS`/`OUTBOX_PRODUCER_*`/`PAYMENT_APPROVAL_LIMIT` 등)는 앱 기본값 사용 → ConfigMap에서 생략

### 무엇을 만들었나 
- **조각0**: kind 클러스터(`cluster.yaml`: control-plane + worker 2) + `commerce` namespace
- **조각1**: product-db(StatefulSet + PVC 1Gi + Secret + headless Service). PVC `data-product-db-0` Bound
- **조각2**: product-service(Deployment + ClusterIP Service + ConfigMap + probe 3종). → **CrashLoopBackOff 겪음(TS-10)**, 임시로 컨슈머 OFF
- **조각 kafka**: kafka(StatefulSet KRaft 단일 노드 + headless Service + PVC). advertised = 파드 안정 DNS `kafka-0.kafka.commerce.svc.cluster.local:9092`. headless에 `publishNotReadyAddresses: true`로 컨트롤러 자기참조 부팅 회피 → **조각2의 컨슈머 부활**
- **조각3**: order/payment + 각 DB(product 패턴 그대로 재사용, 값만 교체). order만 `PRODUCT_SERVICE_URL`(동기 호출 대상) 추가, payment는 이벤트 구동이라 더 단순

### 막힌 것 → 트러블슈팅
- **TS-10 — 조각2 product-service `CrashLoopBackOff`.** 원인: `StockCommandListener @KafkaListener`의 컨테이너가 부팅 끝에 start → `kafka:9092` DNS resolve 시도 → **클러스터에 kafka Service가 없어** hard fail(`No resolvable bootstrap urls`) → context 기동 실패. **compose에선 kafka 컨테이너가 늘 있어 숨어 있던 부팅 의존성**이 k8s에서 드러난 것. 임시 해결: ConfigMap `SPRING_KAFKA_LISTENER_AUTO_STARTUP: "false"` → 앱은 뜨되 컨슈머만 정지. **근본 해결은 조각 kafka에서 브로커를 세우고 이 줄을 제거**(루프 종료).
- **TS-11 — `kind load`가 `content digest ... not found`로 실패.** 원인: Docker Desktop의 **containerd 이미지 스토어** + 멀티플랫폼 이미지(kafka) **공개 이미지는 load 자체가 불필요**(노드가 직접 pull)라 우회. 로컬 단일 빌드 이미지(order/payment)는 정상 로드됨

### 직접 관찰한 것
- **순서의 보상 (조각2 ↔ 조각3 대비, 이 단계의 핵심 체감).** kafka를 먼저 세운 뒤 올린 order/payment는 **`AUTO_STARTUP=false` 없이 한 번에 `1/1 Running`** — 부팅 시 `kafka:9092`가 풀려 컨슈머가 바로 기동(`order: stock-replies/payment-replies`, `payment: payment-commands/payment-refund-commands` partitions assigned). **"의존 대상을 먼저 세우면 뒤따르는 서비스는 같은 고통을 안 겪는다"** 를 CrashLoop 유무로 눈으로 확인.
- **advertised listener 설계가 실제로 동작(TS-1 재림).** 컨슈머 로그의 `currentLeader=...kafka-0.kafka.commerce.svc.cluster.local:9092` — 클라이언트가 `kafka:9092`(bootstrap)로 붙은 뒤 브로커가 알려준 **파드 안정 DNS**로 재접속. advertised가 틀렸으면 여기서 깨졌을 것.
- **사가 전체가 클러스터 안에서 동작(port-forward로 주문 투입).**
  - **보상 경로**: 빈 product-db(시드 없음) → `productId` `PRODUCT_001`(NOT_FOUND) → `StockProcessed(FAILED)` → order 보상 트랜잭션 → `RefundPayment` → payment `REFUNDED` → order **`CANCELLED`**.
  - **해피 패스**: product 시드(재고 100) 후 주문 → product `재고 차감 DEDUCTED`(100→98) + payment `APPROVED`(paymentId=2) → order **`CONFIRMED`**.
  - 두 경로 모두 5개 서비스가 참여하고, **traceId가 product·payment 로그에 동일** → 분산 추적 컨텍스트가 Kafka 메시지를 타고 전파됨까지 증명.
- **DB per service가 물리적으로 드러남.** k8s product-db는 compose product-db와 **다른 PVC(다른 디스크)** 라 시드가 없어 첫 주문이 NOT_FOUND. "각 서비스는 자기 DB만 본다"가 디스크 단위로 보였다.

### 배운 것 
- **compose가 가려주던 부팅 의존성이 k8s에선 터진다.** compose의 암묵적 "컨테이너 항상 존재"가 사라지면 서비스 간 부팅 결합이 명시적 실패(CrashLoop)로 드러나고, 그걸 **인프라를 올바른 순서로 세워** 해소한다
- **앱 = Deployment / 상태 = StatefulSet** 의 갈림은 "안정적 정체성·전용 디스크가 필요한가"로 결정된다. kafka advertised (`publishNotReadyAddresses`)까지 가서 체득
- **이미지 출처(로컬 빌드 vs 공개)가 배포 절차를 가른다** — load 필요/불필요, 그리고 kind load의 containerd 함정(TS-11)
- **번역이 반복되며 손에 익는다.** compose 블록 → 매니페스트 여러 kind를 product→order→payment 세 번 반복하니 패턴이 몸에 남았다

### gateway → Ingress (Ingress ≠ API Gateway)

**직전의 고통.** 조각3까지는 클러스터 안 서비스를 바깥에서 두드리려면 `kubectl port-forward svc/order-service 8080`을 **서비스마다 따로** 띄워야 했다. compose 로 구성했을땐 `gateway-service` 컨테이너 하나(+ 호스트 포트 매핑)가 모든 API의 단일 입구였는데, 그 "한 입구"가 k8s로 오면서 사라졌다

**핵심 개념 — 두 개의 "게이트웨이"는 다른 레이어다.**:

| | 정체 | 하는 일 | 우리 것 |
|---|---|---|---|
| **Ingress** | k8s 네이티브 **엣지(L7)** | "바깥 → 클러스터 안 *어느 Service로*" 만 | `ingress.yaml` (규칙) + `ingress-nginx` (컨트롤러) |
| **API Gateway** | **앱 레벨** 라우터 | path별 서비스 선택 + **서킷브레이커**(Step5c) + 트레이싱 시작 | `gateway-service` (Spring Cloud Gateway) |

- 또 하나의 분리: **Ingress 오브젝트 ≠ Ingress 컨트롤러.** `ingress.yaml`은 "규칙(데이터)"일 뿐이고, 실제 트래픽은 `ingress-nginx-controller`(nginx Pod)가 그 규칙을 읽어 처리한다. **컨트롤러가 없으면 ingress.yaml은 아무 효과 없음.**

**설계 결정 — A안(Ingress → gateway → 서비스).** Ingress가 path 라우팅까지 직접 하면 gateway를 버릴 수 있지만(B안), 그러면 Step5c에서 만든 **서킷브레이커가 통째로 사라진다.** 그래서 gateway-service를 **그냥 또 하나의 Deployment로** k8s에 올리고(DB 없으니 Secret/datasource 없는 stateless 버전), Ingress는 **전부(`path: /`) gateway로 던지기만** 한다. → Ingress 규칙이 한 줄로 끝나고, "엣지 vs 앱 라우터"의 레이어 분리가 코드로 드러난다

**kind 특유의 준비.** Ingress가 `localhost:80`으로 닿으려면 `cluster.yaml`에 두 가지를 선언 (노드 생성 시점에만 적용 → **클러스터 재생성 필요**):
- `extraPortMappings` 80/443 — kind 노드는 도커 컨테이너라, 이게 없으면 호스트의 `localhost:80`이 노드 안으로 못 들어간다.
- `node-labels: ingress-ready=true` (control-plane) — ingress-nginx 컨트롤러가 **이 라벨 붙은 노드에** 떠야 위 포트매핑과 짝이 맞는다.

**무엇을 만들었나.** `gateway-service-config.yaml`(ConfigMap: SERVER_PORT + ORDER/PRODUCT/PAYMENT_SERVICE_URI + ZIPKIN), `gateway-service.yaml`(Deployment + ClusterIP Service, **DB 없어 order 패턴에서 Secret/datasource 제거**), `ingress.yaml`(`path: /` Prefix → `gateway-service:8080`). + `cluster.yaml`에 extraPortMappings·ingress-ready 추가

**막힌 것 → TS-12.** ingress-nginx `main` 매니페스트가 옛 kind 버전에 있던 `nodeSelector: ingress-ready=true`를 빼버려서, 컨트롤러가 라벨 없는 **worker에 스케줄** → control-plane의 포트매핑과 노드가 어긋나 `localhost:80`이 불통(`kubectl wait`가 영영 안 끝남). 컨트롤러 Deployment에 nodeSelector를 patch해 control-plane으로 재스케줄시켜 해결

**직접 관찰한 것.** 컨트롤러를 control-plane(`1/1`)으로 옮긴 뒤, **port-forward 0개로 `localhost:80` 한 입구**에서 사가 양 경로가 다 돌았다:
- 보상: 재고 0(시드 필드명 `stockQuantity` 실수) → 주문1 `CANCELLED`
- 해피: 재고 100 시드 → 주문2 `CONFIRMED` + 상품 재고 100→**97**
- 둘 다 `Ingress → gateway → order → kafka → product/payment` 경로. "서비스마다 port-forward" 고통이 한 입구로 사라졌다

**배운 것 (조각4 시점).**
- **Ingress와 API Gateway는 경쟁 관계가 아니라 다른 층이다.** 가장 흔한 혼동 — Ingress(엣지: 바깥→안)와 앱 게이트웨이(라우팅+회복탄력성)는 보통 **같이** 쓴다. A안이 그걸 코드로 보여준다
- **선언적 오브젝트(Ingress)는 그걸 실행하는 컨트롤러가 있어야 의미가 있다.** k8s 곳곳의 패턴(오브젝트=의도 / 컨트롤러=실행)을 Ingress에서 다시 확인
- **kind에서 "바깥에서 닿기"는 노드=컨테이너라는 사실과 직결**(extraPortMappings + 컨트롤러 배치). 클라우드 LoadBalancer가 공짜로 해주던 걸 손으로 엮어보며 이해

### 조각5 — HPA (HorizontalPodAutoscaler, 부하 따라 자동 스케일)

**직전의 고통.** 지금까지 모든 서비스가 `replicas: 1` 고정이었다. Step7에서 "부하를 주면 버거워진다"(lag 폭증)를 측정했고, 그때 해법은 컨슈머 concurrency를 **사람이 미리 정한 고정값**으로 올리는 것이었다. HPA의 동기는 그 한 단계 위 — **트래픽에 따라 Pod 수 자체를 사람 손 안 대고 자동으로 늘렸다 줄였다** 하는 것. compose로는 불가능한, k8s를 쓰는 핵심 이유

**핵심 개념 — 맞물려야 도는 4개.**
- **HPA 오브젝트** = "order-service를 CPU 50% 기준 1~5개로 유지해라"는 선언. **HPA 컨트롤러**(컨트롤 플레인 내장)가 주기적으로 CPU를 보고 replica를 조절한다(Ingress와 같은 오브젝트+컨트롤러 패턴). 공식: `원하는 수 = ceil(현재 수 × 현재사용률/목표사용률)`
- **metrics-server**(별도 애드온) = HPA의 "눈". 각 노드 kubelet에서 Pod CPU/메모리를 긁어 `metrics.k8s.io`로 공급. k8s 기본엔 없어 따로 설치. kind에선 kubelet 인증서가 self-signed라 `--kubelet-insecure-tls` 패치 필요
- **⚠️ `resources.requests.cpu`** = HPA "%"의 **분모**. CPU 50%는 절대값이 아니라 requests 대비 비율이다. 이게 없으면 HPA가 `<unknown>`을 띄우고 **스케일을 안 한다.** → 대상 Deployment에 requests를 먼저 박는 게 숨은 전제조건

**무엇을 만들었나.** 대상은 **order-service**(주문 POST를 직접 받고 stateless·사가 시작 일꾼). ① `order-service.yaml`에서 고정 `replicas` 제거(HPA가 replica를 "소유" — 안 그러면 apply마다 1로 리셋돼 HPA와 싸움) + `resources` 추가(`requests cpu 200m/mem 512Mi`, `limits cpu 1`; 메모리 limit은 JVM OOMKill 위험으로 생략). ② `order-service-hpa.yaml`(autoscaling/v2, min 1/max 5, CPU 50%, `behavior`로 증설=즉시·감축=300s 안정화 후 1개씩)

**직접 관찰한 것**
- **배선 확인**: apply 후 HPA TARGETS가 `<unknown>` → `cpu: 20%/50%`(40m/200m)로 바뀜 = requests + metrics-server + HPA 세 개가 물렸다는 증거
- **스케일업**: k6로 150 RPS(120s)를 `localhost`(ingress)에 꽂자, HPA 이벤트가 **15초 간격으로 `New size: 2 → 4 → 5`**(scaleUp 100%/15s 정책 그대로, max 5 캡). 피크 `216m/200m = 108%`, `ScalingLimited: TooManyReplicas`(더 늘리고 싶지만 상한). 파드가 1→2→4→5로 순차 Ready
- **과도기의 흔적**: k6 실패율 16.77%인데 p95는 **27ms**. 지연이 아니라 — 부하가 0→150으로 순간에 꽂혀 **오토스케일이 따라잡기 전(~45s) 1개 파드가 다 받다가** gateway 서킷브레이커가 일부를 떨군 것. 5개로 퍼진 뒤 안정. *HPA는 정상, 실패는 "스케일이 따라잡기 전 창"의 자연스러운 결과.*
- **스케일다운의 보수성**: 부하 종료 후 CPU가 `8%`로 떨어졌는데도 **REPLICAS는 한동안 5 유지** — `scaleDown.stabilizationWindowSeconds: 300`이 "혹시 또 몰릴라" 5분 지켜보는 것. 이게 없으면 부하가 출렁일 때마다 5↔1 요동(flapping). **자원 낭비 ↔ 안정성 트레이드오프**를 눈으로

**배운 것**
- **HPA는 혼자 못 돈다.** metrics-server(눈) + resources.requests(분모) + HPA(두뇌)가 다 있어야 한다 — 하나만 빠져도 `<unknown>`. "선언 하나 = 동작"이 아니라 **여러 조각의 합**.
- **스케일 정책은 트레이드오프의 명시화다.** 증설은 빠르게(가용성), 감축은 느리게(안정성) — `behavior`가 그 의사결정을 코드로 박는 자리.
- **오토스케일은 만능이 아니다.** 순간 스파이크는 스케일이 따라잡기 전 일부 실패가 난다(과도기). 그래서 현실에선 HPA + 서킷브레이커/재시도 + (예측 가능하면) 사전 워밍업을 같이 쓴다.

### 진행 상태 — Step 8 완료 
조각0~5 전부 완료. **kind 위에서 compose 사가 스택 전체가 동일하게 동작**(Deployment/StatefulSet/Service/ConfigMap/Secret + probe), `localhost:80` 단일 입구(Ingress→gateway), order-service가 부하에 따라 **1↔5 자동 스케일**. Step6~8 확장 트랙(모니터링 → Kafka 성능 → 쿠버네티스)를 최종적으로 마무리

compose가 "암묵적으로 가려주던 것"(기동 순서·서비스 존재·내부 DNS·입구·스케일)을 k8s는 전부 **명시적 선언**으로 바꾼다. 그 명시성이 그게 곧 자동 복구·분산·자동 스케일이라는 **운영 능력**이다. 로컬 kind라 클라우드가 공짜로 해주던 것(LoadBalancer·인증서)을 손으로 엮으며, 나중에 EKS의 한 줄 뒤에서 무슨 일이 일어나는지를 알게 됐다

---

## Testcontainers — 통합테스트로 "직접 관찰"을 자동화 

### 직전의 고통

프로젝트의 왕관 보석 — Orchestration Saga + Outbox + Inbox 멱등 소비 — 이 **전부 Mockito 단위/`@WebMvcTest` 슬라이스로만** 검증돼 있었다(Kafka·DB 다 가짜). Step4~8 내내 사가가 진짜 도는지는 `port-forward + curl` **수동 관찰**로만 확인했다. 매번 손으로. → 그 "직접 관찰"을 **진짜 MySQL·Kafka를 띄우는 반복 가능한 자동 검증**으로 박제한다.

### 핵심 개념 — Testcontainers ≠ 쿠버네티스

- **Testcontainers**: 테스트가 실행되는 동안만 진짜 인프라(MySQL·Kafka)를 **Docker로 잠깐 띄웠다가 끝나면 자동 제거**하는 라이브러리. 테스트 코드 안 `new MySQLContainer<>(...)` 한 줄이 실제 컨테이너를 띄운다. (Step8의 k8s는 *배포 런타임* — 이건 *테스트*가 진짜 인프라 위에서 도는가의 문제라 서로 무관.)
- **왜 진짜여야 하나**: mock엔 경쟁·행 잠금·직렬화·비동기 배달이 없다. "동시성 안전"·"이벤트가 실제로 흐름"은 **진짜 인프라 위에서만** 증명된다. H2로도 안 된다(락 동작이 InnoDB와 다름 → 거짓 안심).
- **새 도구 3종**: `@DynamicPropertySource`(컨테이너의 랜덤 포트를 `spring.*` 설정에 주입) · **Awaitility**(비동기 결과를 "될 때까지 poll") · `CountDownLatch`(스레드를 출발선에 세웠다 동시 발사).

### 설계 결정

- 서비스별 독립 모듈이라 통합테스트도 **서비스별**. 난이도 순 3조각: MySQL만 → Kafka 합류 → 둘 다.
- 브로커는 프로덕션 이미지(apache/kafka)가 아니라 **안정적으로 뜨는** `ConfluentKafkaContainer` 채택(TS-14).
- Docker API 버전은 각 build.gradle의 test 태스크에 `api.version=1.44`로 핀(TS-13).

### 무엇을 만들었나 

- **조각1 — product 재고 차감 원자성 (real MySQL)**: 재고 50에 **100 스레드 동시 차감** → 원자적 `UPDATE ... WHERE stockQuantity >= :qty`가 오버셀을 막는지. `CountDownLatch`로 100개를 출발선에 세웠다 동시 발사, 각 호출은 독립 트랜잭션(`TransactionTemplate`). Kafka 리스너는 `auto-startup=false`로 꺼 둠(브로커 없이 부팅).
- **조각2 — payment 결제 명령 처리 (real Kafka + MySQL)**: `payment-commands` 발행 → 앱이 소비 → `payment-replies` 발행을 끝-끝. 테스트가 앱 `KafkaTemplate`로 명령을 넣고, raw `KafkaConsumer`로 응답을 **Awaitility로 기다려** 검증. APPROVED/FAILED 두 경로.
- **조각3 (왕관 보석) — order 사가 오케스트레이션 (real Kafka + MySQL)**: order만 띄우고 **테스트가 payment·product를 연기**. `createOrder` → Outbox 릴레이가 명령을 실제 발행(PENDING→SENT) → 테스트가 reply를 주입 → 오케스트레이터가 상태전이·다음 명령 발행. 해피패스(CONFIRMED)/결제거절(CANCELLED)/재고실패 보상(환불+CANCELLED) 3경로.

### 직접 관찰한 것

- **오버셀은 없다 (조각1)**: 100 요청 중 정확히 50 성공/50 실패, 최종 재고 0. mock으론 절대 못 낼 결과 — InnoDB가 같은 로우 UPDATE를 행 단위로 직렬화하는 걸 진짜로 확인.
- **비동기는 "즉시"가 아니다 (조각2)**: `assertThat`을 바로 못 쓰고 `await().atMost(20s).until(...)`로 응답 도착을 기다려야 했다. 그 대기 자체가 이벤트 기반의 본질을 코드로 드러낸다.
- **Outbox는 Kafka + DB 양쪽에서 증명 (조각3)**: 명령이 Kafka에 도착했나(발행됨) + outbox 행이 `SENT`로 바뀌었나(마킹됨). `@Scheduled` 릴레이가 테스트 안에서도 1초마다 돌며 store-and-forward를 완주.
- **"이웃 서비스 연기"로 오케스트레이터만 격리 (조각3)**: 진짜 payment/product 없이, 그 토픽의 producer/consumer가 돼서 order의 사가 전 경로(정상·보상)를 자동으로 돌렸다.

### 막힌 것 → 트러블슈팅

- **[TS-13]** `Could not find a valid Docker environment` — 실은 Docker 29(Min API 1.44) > docker-java 기본 API 버전이라 데몬이 HTTP 400. `api.version=1.44` 시스템 프로퍼티로 핀
- **[TS-14]** apache/kafka `KafkaContainer`가 `advertised.listeners=0.0.0.0`로 기동 실패(TS-1의 데자뷰). `ConfluentKafkaContainer`로 교체

### 배운 것

- **정교한 패턴 검증은 통합테스트로** 정교한 패턴일수록(Saga/Outbox) mock 검증은 "구조는 맞는데 진짜 도는지는 모른다"에 그친다. 진짜 인프라 위 통합테스트라야 "돈다"를 *볼* 수 있다
- **통합테스트 3대 도구 세트**: 컨테이너(진짜 인프라) + `@DynamicPropertySource`(랜덤 포트 주입) + Awaitility(비동기 대기)
- **테스트가 이웃 서비스를 연기**하는 패턴은 마이크로서비스 통합테스트의 핵심 — 전체 스택을 안 띄우고 한 서비스의 협력 계약만 격리 검증
- **한 번 배운 원리는 도구가 바뀌어도 통한다** — `advertised.listeners` 규칙(TS-1)이 Testcontainers Kafka(TS-14)에서 그대로 재림, k8s에서 겪은 "리스너 컨테이너 start가 부팅을 막는다"(TS-10)가 조각1의 `auto-startup=false`로 재활용
- **수동 관찰 → 자동 검증으로의 승격.** Step4~8에서 손으로 확인하던 걸 이제 CI(이미 있는 GitHub Actions)가 매번 대신 확인한다 = 회귀 방지망

---

## 지금까지 관통하는 큰 그림

처음엔 "MSA = 서비스를 잘게 쪼개면 좋아진다"고 생각했지만, 실제로 겪어보니:

1. **쪼개는 순간 결합의 형태가 바뀐다.** 모놀리식의 in-process 호출이 → 네트워크 호출(실패 가능) → 메시지(비동기, 지연/불일치 가능)로. 매 단계가 **새로운 실패 모드**를 데려온다.
2. **각 인프라 조각은 "직전의 고통"에 대한 답이다.** Kafka를 "좋아 보여서" 넣은 게 아니라, 동기 호출의 시간 결합이 아파서 넣었다. 이 순서를 지켜야 *왜* 필요한지가 몸에 남는다.
3. **트레이드오프에 공짜가 없다.** 동기(일관성↑, 결합↑) ↔ 비동기(결합↓, 일관성↓). 어느 쪽도 정답이 아니고, **무엇을 포기할지 고르는 것**이 설계다.
4. **관찰 가능성(observability)이 학습의 핵심 도구** probe, healthcheck, 로그(`[order] 발행 → [product] 수신`), consumer offset/lag — 이게 없었으면 "비동기가 됐다"를 *믿을* 수만 있고 *볼* 수는 없었다. Step 6에서 이걸 메트릭·로그·트레이스 삼각형으로 제대로 깔았다.

---
