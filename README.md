# commerce-msa

모놀리식 → MSA 전환을 단계적으로 학습하는 프로젝트. 도메인은 커머스(주문-결제-재고).
전부 로컬 docker-compose에서 돌린다. 클라우드 배포 없음.

## 학습 로드맵
1. **[현재] 서비스 1개 + DB 1개** — Spring Boot를 compose로 띄우기 (워밍업)
2. 서비스 2개 + DB 2개, REST 동기 호출 — "남의 DB JOIN 못 함", "쟤 죽으면 나도 죽음" 체감
3. 동기 호출 하나를 Kafka 이벤트로 전환
4. Saga(choreography vs orchestration), Outbox, eventual consistency
5. API Gateway, 분산 추적, Circuit Breaker(Resilience4j)

## 지키는 습관 (12-factor / k8s 대비)
- 설정(호스트명/포트/DSN)은 전부 환경변수로 외부화
- 서비스는 stateless. 상태는 DB/Kafka에만
- Actuator liveness/readiness probe → compose healthcheck, 나중에 k8s probe로 전환
- 고정 IP 금지. 서비스 이름(DNS)으로만 통신

---

## 1단계 실행 방법

```bash
# 1) 환경변수 준비
cp .env.example .env

# 2) 빌드 + 기동 (order-db가 healthy해진 뒤 order-service가 뜬다)
docker compose up --build

# 3) 상태 확인 (다른 터미널에서)
docker compose ps
```

### 동작 확인

```bash
# liveness: 프로세스 살아있나
curl http://localhost:8080/actuator/health/liveness

# readiness: 요청 받을 준비 됐나 (DB까지 OK여야 UP)
curl http://localhost:8080/actuator/health/readiness

# 주문 생성 
curl -i -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId": 1,
    "items": [
      {"productId": 100, "productName": "키보드", "unitPrice": 30000, "quantity": 2},
      {"productId": 200, "productName": "마우스", "unitPrice": 15000, "quantity": 1}
    ]
  }'

# 주문 조회 
curl http://localhost:8080/api/v1/orders/1

# API 문서 / 브라우저로 직접 테스트: Swagger UI
#   http://localhost:8080/swagger-ui.html
```

### 직접 관찰해볼 것
- `docker compose up` 로그에서 **order-db가 healthy가 될 때까지 order-service가 기다리는지**
- DB 컨테이너를 죽이면(`docker compose stop order-db`) **readiness가 DOWN으로 바뀌는지**
  (`curl .../actuator/health/readiness`) — 이게 2단계 "의존 대상이 죽으면 나도 영향받음"의 예고편

### 정리
```bash
docker compose down        # 컨테이너 제거 (DB 데이터는 볼륨에 남음)
docker compose down -v     # 볼륨까지 삭제 (DB 데이터 초기화)
```