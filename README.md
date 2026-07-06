# commerce-msa

> **모놀리식 → MSA 전환 관련 학습 프로젝트** 도메인은 커머스(주문–결제–재고)
> 각 인프라 조각을 **직전 단계에서 겪은 고통의 결과**로만 도입했고, 매 단계를 로그·메트릭·트레이스로 **직접 관찰**하며 구현 및 학습 

<p>
<img alt="Java" src="https://img.shields.io/badge/Java-21-orange">
<img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.5.7-6DB33F?logo=springboot&logoColor=white">
<img alt="Kafka" src="https://img.shields.io/badge/Apache%20Kafka-KRaft-231F20?logo=apachekafka&logoColor=white">
<img alt="MySQL" src="https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql&logoColor=white">
<img alt="Docker" src="https://img.shields.io/badge/Docker%20Compose-2496ED?logo=docker&logoColor=white">
<img alt="Kubernetes" src="https://img.shields.io/badge/Kubernetes-kind-326CE5?logo=kubernetes&logoColor=white">
<img alt="Testcontainers" src="https://img.shields.io/badge/Testcontainers-integration-2A2D3A">
</p>

**한눈에 담긴 것**

- 🧭 **Orchestration Saga** — 중앙 오케스트레이터가 주문→결제→재고 흐름과 **보상 사슬**을 command/reply로 조율
- 📦 **Outbox + Inbox 멱등 소비** — dual-write 문제와 at-least-once 중복을 짝으로 해소한 **신뢰성 메시징**
- 🔭 **Observability 삼각형** — Prometheus(metrics) · Loki(logs) · Zipkin(traces), traceId가 Kafka 헤더를 타고 서비스 간 전파
- ⚡ **Kafka 성능 튜닝** — 파티션 · consumer concurrency · producer(linger/batch/compression)를 부하(k6)로 측정하며 조정
- ☸️ **Kubernetes(kind)** — Deployment/StatefulSet/Service/ConfigMap/Secret + probe + **Ingress + HPA**(부하 따라 1<->5 오토스케일)
- 🚪 **API Gateway** — Spring Cloud Gateway 단일 입구 + **Resilience4j Circuit Breaker**
- 🧪 **Testcontainers 통합테스트** — 진짜 MySQL·Kafka를 띄워 Saga/Outbox/동시성을 **자동 검증** 

> 단계별 "어떻게 이해해 갔나"의 과정은 **[LEARNING_JOURNEY.md](./LEARNING_JOURNEY.md)**, 실제로 막힌 지점(증상→원인→해결→교훈)은 **[TROUBLESHOOTING.md](./TROUBLESHOOTING.md)** (TS-1 ~ TS-14)

---

## 목차

- [학습 로드맵](#학습-로드맵)
- [기술 스택](#기술-스택)
- [시스템 구성](#시스템-구성)
- [핵심 ① — Orchestration Saga + 보상](#핵심--orchestration-saga--보상)
- [핵심 ② — 신뢰성 메시징 (Outbox + Inbox)](#핵심--신뢰성-메시징-outbox--inbox)
- [핵심 ③ — Observability (metrics · logs · traces)](#핵심--observability-metrics--logs--traces)
- [핵심 ④ — Kubernetes (kind)](#핵심--kubernetes-kind)
- [핵심 ⑤ — 통합테스트 (Testcontainers) + CI](#핵심--통합테스트-testcontainers--ci)
- [실행 방법](#실행-방법)
- [동작 확인 시나리오](#동작-확인-시나리오)
- [디렉터리 구조](#디렉터리-구조)

---

## 학습 로드맵

각 단계는 **직전 단계의 불편함**을 해소하기 위해 구성 -> 직전 단계에 기반한 순서 자체를 커리큘럼으로 구성 

### Phase 1 — MSA 패턴 체득
| 단계 | 내용 | 해소한 고통 |
|---|---|---|
| **1** | 서비스 1개 + DB 1개 (워밍업) | — |
| **2** | 서비스 2개 + DB 2개, REST 동기 호출 | "남의 DB는 JOIN 못 함", "쟤 죽으면 나도 죽음" |
| **3** | 동기 호출 → **Kafka 이벤트**로 전환 (3a 발행/수신 → 3b 동기 제거) | 시간 결합(느린/죽은 의존)의 장애 전파 |
| **4** | **Saga · Outbox · eventual consistency** (4a choreography → 4b 3-step 보상 사슬 → 4c orchestration → 4d 멱등 inbox → 4e Outbox) | 분산 트랜잭션의 부재, 중복 소비, dual-write |
| **5** | **API Gateway · 분산 추적 · Circuit Breaker** (5a~5c) | 입구 분산, 요청 추적 불가, 연쇄 장애 |

### Phase 1.5 — 확장 
| 단계 | 내용 | 해소한 고통 |
|---|---|---|
| **6** | **모니터링** — Prometheus + Grafana + Loki/Promtail (observability 삼각형) | "됐다"를 *믿을* 순 있어도 *볼* 수 없었음 |
| **7** | **Kafka 성능** — kafka-exporter(lag) + k6 부하 → 파티션 · concurrency · producer 튜닝 | 병목이 어디서 쌓이는지 측정·해소 |
| **8** | **쿠버네티스(kind)** — 매니페스트 + probe + Ingress + HPA | compose가 암묵적으로 가려주던 것(기동 순서·DNS·입구·스케일)을 선언형으로 |
| **+** | **Testcontainers 통합테스트** — 진짜 MySQL·Kafka로 Saga/Outbox/동시성 자동 검증 | 왕관 보석이 mock으로만 검증돼 있던 문제 |


---

## 기술 스택

| 범주 | 사용 기술 |
|---|---|
| **Language / Framework** | Java 21, Spring Boot 3.5.7, Spring Cloud Gateway, Spring Data JPA |
| **Messaging** | Apache Kafka 3.9 (KRaft, 주키퍼 없음) |
| **Datastore** | MySQL 8.0 (**DB per service**) |
| **Resilience** | Resilience4j Circuit Breaker, Outbox / Inbox 패턴 |
| **Observability** | Micrometer + Prometheus, Grafana, Loki + Promtail, Zipkin(Brave) 분산 추적 |
| **Container / Orchestration** | Docker Compose, Kubernetes (kind) + Ingress-NGINX + metrics-server + HPA |
| **Test** | JUnit 5, Mockito, **Testcontainers** (MySQL · Kafka), k6(부하), GitHub Actions CI |
| **Build** | Gradle Wrapper 9.2.1 (서비스별 독립 모듈) |

각 서비스 내부는 **DDD 레이어드**(domain / repository / service / controller / dto / messaging / global), 응답은 `CommonResponse<T>`, 예외는 단일 `ApplicationException` + 도메인별 `ErrorCase` enum으로 통일

---

## 시스템 구성

```
                         ┌───────────────────────────────────────────────┐
                         │                Observability                  │
   client               │  Prometheus ◀─scrape─ /actuator/prometheus     │
     │                  │  Grafana ◀ Prometheus + Loki                   │
     ▼                  │  Loki ◀ Promtail(도커 로그)   Zipkin ◀ traces  │
┌──────────────┐        └───────────────────────────────────────────────┘
│ API Gateway  │  :8000        ▲ metrics/logs/traces (전 서비스)
│ (Resilience4j│               │
│  CircuitBrk) │               │
└──────┬───────┘   /api/v1/orders/**   /api/v1/products/**   /api/v1/payments/**
       │                 │                    │                    │
       ▼                 ▼                    ▼                    ▼
   ┌────────────┐   ┌────────────┐      ┌────────────┐      ┌────────────┐
   │   order    │   │  product   │      │  payment   │      │   kafka    │
   │  :8080     │   │  :8081     │      │  :8082     │      │  (KRaft)   │
   │ Saga 오케  │   │  재고      │      │  결제      │      │  command/  │
   │ +Outbox    │   │            │      │  +Inbox    │      │  reply bus │
   └─────┬──────┘   └─────┬──────┘      └─────┬──────┘      └──────┬─────┘
         │ order-db       │ product-db        │ payment-db         │
      ┌──▼──┐          ┌──▼──┐             ┌──▼──┐                 │
      │MySQL│:3306     │MySQL│:3307        │MySQL│:3308            │
      └─────┘          └─────┘             └─────┘                 │
         └──────────────── 모든 서비스 간 통신은 해당 Kafka 버스로 ──┘
```

**서비스 / 포트** (compose 기준, `.env`로 조정 가능)

| 구성요소 | 호스트 포트 | 역할 | 관측 |
|---|---|---|---|
| **gateway-service** | **8000** | 단일 입구, 라우팅 + Circuit Breaker | `/actuator/gateway/routes`, `/actuator/circuitbreakers` |
| order-service | 8080 | 주문 + **Saga 오케스트레이터** + Outbox | [Swagger](http://localhost:8080/swagger-ui.html) |
| product-service | 8081 | 상품/재고 (원자적 차감) | [Swagger](http://localhost:8081/swagger-ui.html) |
| payment-service | 8082 | 결제/환불 + Inbox | [Swagger](http://localhost:8082/swagger-ui.html) |
| order-db / product-db / payment-db | 3306 / 3307 / 3308 | 서비스별 전용 MySQL | — |
| kafka | 9094 (호스트 EXTERNAL) | 메시지 버스 (KRaft 단일 브로커) | — |
| **Grafana** | **3000** | 대시보드 (서비스 · Kafka) | admin/admin |
| Prometheus | 9090 | 메트릭 수집 | — |
| Zipkin | 9411 | 분산 추적 UI | — |
| Loki | 3100 | 로그 저장소 | (Grafana에서 조회) |
| kafka-exporter | 9308 | consumer lag 메트릭 | — |

> 컨테이너 안에서는 브로커를 `kafka:9092`(내부 DNS), 맥에서 IDE로 직접 띄울 땐 `localhost:9094`로 접속

---

## 핵심 1 — Orchestration Saga + 보상

서비스 간은 Kafka로 잇되, **중앙 오케스트레이터(order 내장 `OrderSagaOrchestrator`)가 흐름을 구성**한다. 토픽은 event가 아니라 **명령(command) / 응답(reply)** 채널이다.

```
order(오케스트레이터)  주문(PENDING) → 사가 시작
  └─ ProcessPayment ─▶ payment-commands ─▶ payment: 결제
       └─ PaymentProcessedReply ─▶ payment-replies ─▶ 오케스트레이터
            ├─ APPROVED → DeductStock ─▶ stock-commands ─▶ product: 재고 차감
            │    └─ StockProcessedReply ─▶ stock-replies ─▶ 오케스트레이터
            │         ├─ DEDUCTED → 주문 CONFIRMED (product가 준 실제 단가로 total 재계산)
            │         └─ FAILED   → RefundPayment ─▶ payment-refund-commands ─▶ 결제 REFUNDED + 주문 CANCELLED  (보상2)
            └─ FAILED   → 주문 CANCELLED  (보상1, 재고 진입 안 함)
```

- **payment / product는 "명령 받아 처리 → 응답"만** 한다. "다음에 뭘 할지"는 전부 오케스트레이터가 결정 → 서비스끼리 서로를 모른다 (choreography 대비 결합↓, 흐름 가시성↑)
- 결제가 재고보다 **먼저**라, 주문 생성 시 클라가 보낸 **예상 단가**로 결제 금액을 만들고, 상품의 **진짜 이름/단가는 재고 차감 후 product가 채운다** (order는 상품 테이블을 JOIN하지 않는다 — **DB per service**)
- order·payment는 타입이 다른 토픽을 둘씩 구독하므로 **타입별 컨슈머 팩토리**(`KafkaConsumerConfig`)로 역직렬화를 분리
- **choreography(4b) ↔ orchestration(4c) 두 방식을 다 구현·대조**했다 — 각각의 장단(암묵적 흐름 vs 중앙 조율)을 코드로 비교. → [LEARNING_JOURNEY.md](./LEARNING_JOURNEY.md)

## 핵심 2 — 신뢰성 메시징 (Outbox + Inbox)

**Kafka는 at-least-once다.** 두 패턴이 짝을 이뤄 정합성을 지킨다

- **Outbox (4e)** — order는 명령을 Kafka로 직접 쏘지 않고 `outbox_messages` 테이블에 **주문 상태 변경과 같은 트랜잭션으로 적재**한다(`SagaCommandPublisher`). 별도 릴레이(`OutboxRelay`, `@Scheduled`)가 `PENDING` 행을 읽어 발행 후 `SENT`로 마킹 → **저장과 발행이 원자적**이라 "DB는 커밋됐는데 발행 직전 죽음(발행 누락)"·"발행됐는데 DB 롤백(유령 결제)"을 둘 다 막는다.
- **멱등 소비 / Inbox (4d)** — 메시지마다 `messageId`(UUID)를 싣고, 처리한 id를 각 서비스 DB의 `processed_messages`에 PK로 기록한다. 재배달되면 `existsById`로 걸러 **부수효과를 한 번만** 낸다(이중 결제/차감/환불 차단). 부수효과와 기록은 같은 트랜잭션.
- 릴레이의 재발행(at-least-once)을 소비 측 inbox가 흡수 → **둘이 한 세트**
- **재고 차감은 경쟁 자원**이라 `find→set→save`가 아니라 **원자적 UPDATE**(`UPDATE ... SET stock = stock - :n WHERE id = :id AND stock >= :n`, 반환 0이면 부족)로 오버셀을 방지

## 핵심 3 — Observability (metrics · logs · traces)

| 축 | 도구 | 내용 |
|---|---|---|
| **Metrics** | Prometheus + Grafana | 4서비스 `/actuator/prometheus` scrape, p95/p99 히스토그램, Kafka lag 대시보드(kafka-exporter) |
| **Logs** | Loki + Promtail | 도커 소켓으로 컨테이너 stdout을 tail → Loki, Grafana에서 조회 |
| **Traces** | Zipkin + Micrometer/Brave | 요청 1건의 전 구간 추적. **traceId가 Kafka 헤더를 타고 서비스 간 전파** |

- Grafana의 Loki **derived field**로 로그의 traceId를 클릭하면 Zipkin 트레이스로 점프 → **로그 ↔ 트레이스 상관**
- **Outbox의 함정(TS-7)**: 릴레이가 다른 스레드/나중에 발행해 trace가 끊기는 문제를, 적재 시점의 trace context를 outbox 행에 저장했다가 릴레이가 복원해 **하나의 trace로 잇는다**

## 핵심 4 — Kubernetes (kind)

로컬 **kind** 클러스터에 전체 사가 스택을 배포. compose가 암묵적으로 해주던 것을 전부 **선언형 매니페스트**로 전환 

- 앱은 **Deployment**, MySQL·Kafka는 **StatefulSet + PVC**(상태), 설정은 **ConfigMap**, 비밀번호는 **Secret**
- actuator **liveness/readiness → k8s probe**로 전환(§ 12-factor 습관의 회수)
- **Ingress-NGINX**로 `localhost:80` 단일 입구 → gateway-service
- **HPA** — order-service를 CPU 부하에 따라 **1↔5 자동 스케일**(metrics-server + resources.requests + HPA 3박자). k6 150 RPS로 `New size 2→4→5` 스케일업, 부하 종료 후 안정화 윈도우로 보수적 스케일다운을 직접 관찰
- 매니페스트: [`k8s/manifests/`](./k8s/manifests) · 클러스터: [`k8s/kind/cluster.yaml`](./k8s/kind/cluster.yaml).

## 핵심 5 — 통합테스트 (Testcontainers) + CI

Saga·Outbox·멱등이 mock으로만 검증되던 걸, **진짜 인프라를 띄워 통합테스트**로 구성 

| 테스트 | 띄우는 인프라 | 증명 |
|---|---|---|
| `ProductStockConcurrencyIntegrationTest` | **MySQL** | 재고 50에 100스레드 동시 차감 → 정확히 50성공/최종 0 (**오버셀 없음**) |
| `PaymentCommandFlowIntegrationTest` | **Kafka + MySQL** | `payment-commands` 소비 → `payment-replies` 발행 (Awaitility 비동기 대기) |
| `OrderSagaIntegrationTest` | **Kafka + MySQL** | **테스트가 payment·product를 연기** → Outbox(PENDING→SENT) + 사가 3경로(CONFIRMED/취소/보상) |

- `@DynamicPropertySource`로 컨테이너의 랜덤 포트를 Spring에 주입, **Awaitility**로 비동기 결과 대기
- **GitHub Actions CI**([`.github/workflows/ci.yml`](./.github/workflows)) — 4서비스 매트릭스 빌드
- 이 과정의 실전 트러블(Docker 29 API 버전 스큐 TS-13, Kafka 컨테이너 이미지 TS-14)은 [TROUBLESHOOTING.md](./TROUBLESHOOTING.md)

---

## 실행 방법

```bash
# 1) 환경변수 준비 (최초 1회)
cp .env.example .env

# 2) 빌드 + 기동 (각 DB·kafka가 healthy해진 뒤 서비스가 뜬다) — 약 14개 컨테이너
docker compose up --build

# 3) 상태 확인 (다른 터미널)
docker compose ps
```

기동 후 접속: **Gateway** http://localhost:8000 · **Grafana** http://localhost:3000 · **Zipkin** http://localhost:9411 · Swagger(서비스별) `:8080/8081/8082/swagger-ui.html`

> 아래 시나리오는 **게이트웨이 단일 입구(:8000)** 로 호출한다(각 서비스 직접 포트로도 동일하게 동작)

## 동작 확인 시나리오

### 1. 정상 흐름 → CONFIRMED

```bash
# 상품 등록 (재고 10) — 응답의 data.productId 확인 (fresh start면 1)
curl -X POST http://localhost:8000/api/v1/products -H 'Content-Type: application/json' \
  -d '{"name":"키보드","price":30000,"stockQuantity":10}'

# 주문 생성 — productId·quantity·예상 단가(unitPrice). 비동기라 즉시 PENDING(200)
curl -X POST http://localhost:8000/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":2,"unitPrice":30000}]}'

# 잠시 후 조회 — PENDING → CONFIRMED, 이름/단가 채워지고 total 재계산, 재고 10→8
curl http://localhost:8000/api/v1/orders/1
curl http://localhost:8000/api/v1/products/1
```

흐름 로그: `docker compose logs -f order-service payment-service product-service`
→ `[order] 사가 시작 → 결제 명령 → 승인 → 재고 차감 명령 → 차감 → 주문 확정` 한 바퀴가 오케스트레이터 주도로 보인다.

### 2. 보상1 — 결제 거절 → CANCELLED

```bash
# 예상 단가를 결제 한도(기본 1,000,000) 초과로 → 결제 FAILED
curl -X POST http://localhost:8000/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1,"unitPrice":2000000}]}'
# 결과: 주문 CANCELLED, 결제 FAILED, 재고는 그대로 (재고 단계 진입조차 안 함)
```

### 3. 보상2 — 재고 실패 → 결제 환불 (보상 사슬)

```bash
# 재고보다 많이 주문하되 금액은 한도 이하 → 결제 APPROVED 됐다가 재고에서 실패
curl -X POST http://localhost:8000/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":999,"unitPrice":30000}]}'
# 결과: 주문 CANCELLED + 결제 APPROVED→REFUNDED (이미 한 결제를 거꾸로 되돌림)
curl http://localhost:8000/api/v1/payments
```

### 4. 디커플링 / eventual consistency (product 죽였다 살리기)

```bash
docker compose stop product-service
# 주문 생성 → 여전히 200 (PENDING). step2였으면 503으로 실패했을 것
curl -X POST http://localhost:8000/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1,"unitPrice":30000}]}'
docker compose start product-service
# product가 멈췄던 offset부터 밀린 이벤트를 따라잡아 주문이 PENDING → CONFIRMED로 수렴
```

### 5. 더 관찰해볼 것

- **Outbox 흐름 (4e)**: 주문 직후 `PENDING` → (릴레이 1초 주기) → `SENT`
  ```bash
  docker compose exec order-db mysql -uroot -prootpw orderdb \
    -e "SELECT topic, status, sent_at FROM outbox_messages ORDER BY created_at;"
  ```
- **멱등 소비 (4d)**: 처리된 id는 각 서비스 DB `processed_messages`에 남고, 같은 메시지 재produce 시 `중복 메시지 스킵` 로그와 함께 부수효과가 안 늘어난다.
- **consumer lag / 부하**: Grafana의 Kafka 대시보드 + `loadtest/scripts/order-load.js`(k6)로 유입↑ 시 lag 우상향을 관찰.
- **분산 추적**: Zipkin에서 주문 1건이 gateway→order→(kafka)→payment→product로 이어지는 하나의 trace를 확인.

### 정리

```bash
docker compose down        # 컨테이너 제거 (DB 볼륨 유지)
docker compose down -v     # 볼륨까지 삭제 (상품 id가 1부터 다시 시작)
```

---

## 디렉터리 구조

```
commerce-msa/
├── order-service/       # 주문 + Saga 오케스트레이터 + Outbox (messaging/, messaging/outbox/)
├── product-service/     # 상품/재고 (원자적 차감) + 멱등 소비
├── payment-service/     # 결제/환불 + Inbox
├── gateway-service/     # Spring Cloud Gateway + Resilience4j Circuit Breaker
├── monitoring/          # prometheus.yml, grafana 프로비저닝(대시보드·datasource), loki, promtail
├── loadtest/            # k6 부하 스크립트
├── k8s/
│   ├── kind/cluster.yaml
│   └── manifests/       # Deployment/StatefulSet/Service/ConfigMap/Secret/Ingress/HPA
├── .github/workflows/   # CI 
├── docker-compose.yml   # 전체 스택 (~14 컨테이너)
├── LEARNING_JOURNEY.md  # 단계별 학습 일지 
└── TROUBLESHOOTING.md   # 트러블슈팅 TS-1 ~ TS-14
```

각 서비스는 **독립 Gradle 모듈 + 독립 Dockerfile + 독립 DB**. 서비스 내부는 DDD 레이어드 구조로 통일

---