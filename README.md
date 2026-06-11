# commerce-msa

모놀리식 → MSA 전환을 단계적으로 학습하는 프로젝트. 도메인은 커머스(주문-결제-재고).
전부 로컬 docker-compose에서 돌린다. 클라우드 배포 없음.

## 학습 로드맵
1. ✅ 서비스 1개 + DB 1개 — Spring Boot를 compose로 띄우기 (워밍업)
2. **[현재] 서비스 2개 + DB 2개, REST 동기 호출** — "남의 DB JOIN 못 함", "쟤 죽으면 나도 죽음" 체감
3. 동기 호출 하나를 Kafka 이벤트로 전환
4. Saga(choreography vs orchestration), Outbox, eventual consistency
5. API Gateway, 분산 추적, Circuit Breaker(Resilience4j)

## 지키는 습관 (12-factor / k8s 대비)
- 설정(호스트명/포트/DSN)은 전부 환경변수로 외부화
- 서비스는 stateless. 상태는 DB/Kafka에만
- Actuator liveness/readiness probe → compose healthcheck, 나중에 k8s probe로 전환
- 고정 IP 금지. 서비스 이름(DNS)으로만 통신

---

## 서비스 / 포트

| 서비스 | 호스트 포트 | DB | DB 호스트 포트 | Swagger |
|---|---|---|---|---|
| order-service | 8080 | order-db | 3306 | http://localhost:8080/swagger-ui.html |
| product-service | 8081 | product-db | 3307 | http://localhost:8081/swagger-ui.html |

order-service는 주문 생성 시 product-service를 `http://product-service:8080`(DNS)로 동기 호출해 **재고를 차감하고 상품 이름/가격을 받아온다.** 상품/재고는 product-service만 소유한다(order는 JOIN 불가).

## 실행 방법

```bash
# 1) 환경변수 준비
cp .env.example .env

# 2) 빌드 + 기동 (각 DB가 healthy해진 뒤 각 서비스가 뜬다)
docker compose up --build

# 3) 상태 확인 (다른 터미널에서) — order/product 4개 컨테이너
docker compose ps
```

### 동작 확인 (정상 흐름)

```bash
# 1) 상품 등록 (product-service) — 주문하려면 먼저 상품·재고가 있어야 한다
curl -X POST http://localhost:8081/api/v1/products \
  -H 'Content-Type: application/json' \
  -d '{"name": "키보드", "price": 30000, "stockQuantity": 10}'
curl -X POST http://localhost:8081/api/v1/products \
  -H 'Content-Type: application/json' \
  -d '{"name": "마우스", "price": 15000, "stockQuantity": 5}'

# 2) 상품 목록/재고 확인
curl http://localhost:8081/api/v1/products

# 3) 주문 생성 (order-service) — 이제 productId와 quantity만 보낸다.
#    이름/가격은 order가 product에 물어봐서 채운다. 재고도 이때 차감된다.
curl -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId": 1, "items": [{"productId": 1, "quantity": 2}, {"productId": 2, "quantity": 1}]}'

# 4) 주문 조회 — 항목에 product가 돌려준 이름/가격이 스냅샷으로 박혀 있다
curl http://localhost:8080/api/v1/orders/1

# 5) 재고가 줄었는지 다시 확인 (키보드 10 → 8, 마우스 5 → 4)
curl http://localhost:8081/api/v1/products
```

### 핵심 관찰: product 죽으면 order도 죽는다 (시간 결합)

```bash
# product-service만 내린다 (order-service는 그대로 떠 있다)
docker compose stop product-service

# 주문을 다시 시도 → order는 살아있지만 재고 차감을 못 해서 503으로 실패한다.
curl -i -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId": 1, "items": [{"productId": 1, "quantity": 1}]}'
#   → HTTP 503, code "ORDER_004" (상품 서비스를 호출할 수 없습니다)
#   타임아웃(2초) 안에 빠르게 실패하는지 체감해볼 것.

# 다시 살리면 주문 정상화 
docker compose start product-service
```

### 더 관찰해볼 것
- **재고 부족**: 재고보다 많이 주문 → product가 409, order는 `ORDER_003`(재고 부족)으로 번역.
- **없는 상품**: `productId: 999` 주문 → `ORDER_002`(상품을 찾을 수 없음).
- **분산 트랜잭션 구멍(4단계 복선)**: `OrderService.createOrder` 주석 참고 — 재고는 깎였는데 주문 저장이 실패하면 "재고만 줄고 주문은 없는" 불일치가 남는다. 지금은 일부러 안 막는다.
- **DB 죽이기(1단계 복습)**: `docker compose stop product-db` 후 product의 readiness가 DOWN으로 바뀌는지.

### 정리
```bash
docker compose down        # 컨테이너 제거 (DB 데이터는 볼륨에 남음)
docker compose down -v     # 볼륨까지 삭제 (DB 데이터 초기화)
```