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

## 지금까지 관통하는 큰 그림

처음엔 "MSA = 서비스를 잘게 쪼개면 좋아진다"고 생각했지만, 실제로 겪어보니:

1. **쪼개는 순간 결합의 형태가 바뀐다.** 모놀리식의 in-process 호출이 → 네트워크 호출(실패 가능) → 메시지(비동기, 지연/불일치 가능)로. 매 단계가 **새로운 실패 모드**를 데려온다.
2. **각 인프라 조각은 "직전의 고통"에 대한 답이다.** Kafka를 "좋아 보여서" 넣은 게 아니라, 동기 호출의 시간 결합이 아파서 넣었다. 이 순서를 지켜야 *왜* 필요한지가 몸에 남는다.
3. **트레이드오프에 공짜가 없다.** 동기(일관성↑, 결합↑) ↔ 비동기(결합↓, 일관성↓). 어느 쪽도 정답이 아니고, **무엇을 포기할지 고르는 것**이 설계다.
4. **관찰 가능성(observability)이 학습의 핵심 도구** probe, healthcheck, 로그(`[order] 발행 → [product] 수신`), consumer offset/lag — 이게 없었으면 "비동기가 됐다"를 *믿을* 수만 있고 *볼* 수는 없었다.

---

## 다음 — Step 5 예고

Step 4(Saga)는 4a~4e로 마무리됐다. 보상 루프(4a) → 결제 추가 3-step choreography(4b) → orchestration 대조(4c) → 멱등 소비(4d) → outbox(4e)까지, 분산 트랜잭션의 정합성을 이벤트/사가/inbox/outbox로 닫았다. 남은 것:
- **Step 5 — API Gateway · 분산 추적 · Circuit Breaker(Resilience4j)**: 지금은 서비스마다 포트가 흩어져 있고(8080/8081/8082), 한 주문이 order→payment→product를 거치는 흐름을 로그를 `grep`으로 이어 붙여 본다. 게이트웨이로 진입점을 모으고, 분산 추적(trace id)으로 한 요청의 전체 경로를 한 줄로 잇고, Circuit Breaker로 의존 서비스 장애를 격리한다.
- **(보류) outbox 확장**: payment·product의 reply 발행에 남은 dual-write. 같은 패턴이라 학습 가치가 낮아 미뤘다 — 운영 관점이 필요해지면 그때.
