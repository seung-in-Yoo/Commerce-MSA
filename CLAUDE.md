# CLAUDE.md — commerce-msa

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> Claude Code가 매 세션 자동으로 읽는다. **신규 코드는 이 문서의 룰을 따른다.** 이 프로젝트는 학습용이므로, "좋은 프로덕트"보다 **MSA 패턴/고통점 체득**이 항상 우선한다. 룰을 어길 정당한 사유가 있으면 설명한다.

---

## 0. 이 프로젝트는 무엇인가

**모놀리식 → MSA 전환을 "직접 몸으로 체득"하려고 만든 학습 프로젝트.** 도메인은 커머스(주문-결제-재고). 보상 Saga가 자연스럽게 나와서 학습용으로 선택했다.

- 원본 모놀리식 DDD 프로젝트 **BReady는 따로 있고 안 건드린다.** 여기는 별도 연습 레포.
- **2-Phase 메타플랜**:
  - **[Phase 1 — 지금]** 커머스로 그린필드를 *점진적으로* 구축하며 MSA 패턴을 체득.
  - **[Phase 2 — 나중]** 진짜 BReady를 **Strangler Fig**로 서비스 추출(실무 마이그레이션 연습).
- 전부 로컬 `docker-compose`. **클라우드 배포 없음**

---

## 1. 핵심 원칙 (반드시 지켜)

1. **학습 가치 최우선.** 좋은 프로덕트가 목표가 아니다. 과한 추상화·조기 최적화 금지(**YAGNI**). 지금 단계에 필요 없는 건 만들지 않는다.
2. **한 조각씩 쌓는다.** ❌ 한 턴에 여러 서비스 + DB + Kafka + 게이트웨이를 한꺼번에 scaffold하지 마라. ✅ 각 조각은 **직전 단계의 불편함을 해결하는 형태**여야 한다.
3. **"왜 지금 이게 필요해지는지"를 먼저 설명한다.** 조각(서비스/Kafka/Saga 등)을 추가하기 전에 직전 단계에서 겪은 고통과의 연결고리를 말로 설명한 다음 코드를 쓴다.
4. **막히는 건 실패가 아니라 커리큘럼이다.** (Kafka 중복 소비, 컨테이너 기동 순서, 트랜잭션 경계 깨짐 등) 막히면 같이 푼다. 우회하지 말고 원인을 이해한다.
5. **코드는 요약하지 말고 완전한 형태로 준다.** "...(생략)" 금지. 새 파일/수정 파일은 전체를 보여준다.
6. **커밋·푸시·원격 레포 생성은 항상 사용자가 직접 한다.** Claude는 파일 작성·검증 등 **그 직전까지만** 하고, 사용자가 실행할 **git 명령어만 정리해서** 제시한다(§15). 직접 `git commit`/`git push`/`gh repo create`를 실행하지 않는다.

---

## 2. 기술 스택 

- **Java 21**, **Spring Boot 3.5.7**, **Gradle Wrapper 9.2.1**, **MySQL 8.0**.
- **Kafka는 3단계부터** 도입(지금은 없다 — 미리 깔지 마라).
- 베이스 패키지: `com.commerce.{service}` (예: `com.commerce.order`).
- 빌드 의존성(현재 order-service): `web`, `data-jpa`, `validation`, `actuator`, `micrometer-registry-prometheus`(runtime), `mysql-connector-j`, `lombok`, `spring-boot-starter-test`.
- **각 서비스 내부는 DDD 구조. DB per service**(다른 서비스의 테이블 직접 접근 금지 — §13).

---

## 3. 5단계 로드맵 & 현재 위치

이 **순서대로** 간다. 각 단계는 직전 단계의 고통을 해소한다.

1. **서비스 1개 + DB 1개** (워밍업) — Spring Boot를 compose로 띄우기. 
2. **서비스 2개 + DB 2개, REST 동기 호출** — "남의 DB JOIN 못 함", "쟤 죽으면 나도 죽음" 체감. ← **다음 (§16)**
3. 그 동기 호출 하나를 **Kafka 이벤트로 전환**.
4. **Saga**(choreography vs orchestration), **Outbox**, eventual consistency.
5. **API Gateway**, 분산 추적, **Circuit Breaker**(Resilience4j).

> **현재 위치를 항상 의식한다.** 로드맵상 아직 안 온 인프라(Kafka, 게이트웨이, Saga, 보안/JWT, 마이그레이션 툴 등)는 해당 단계에 도달하기 전엔 추가하지 않는다.

### 1단계 결과물 

```
commerce-msa/
├── docker-compose.yml          # order-db(mysql:8.0) + order-service
├── .env.example / .gitignore / README.md / CLAUDE.md
└── order-service/
    ├── Dockerfile              # 멀티스테이지: gradlew 빌드(JDK) → JRE 실행, curl 포함
    ├── build.gradle            # web/jpa/validation/actuator/mysql/lombok
    └── src/main/
        ├── resources/application.yml   # DB env 외부화, health liveness/readiness(+db) 그룹
        └── java/com/commerce/order/
            ├── OrderApplication
            ├── domain/         # Order(애그리거트 루트) / OrderItem / OrderStatus
            ├── repository/     # OrderRepository
            ├── service/        # OrderService (create / get)
            ├── controller/     # OrderController (POST·GET /api/v1/orders, Swagger)
            ├── dto/            # 요청=record(CreateOrderRequest/OrderLineRequest) / 응답=@Builder(OrderResponse/OrderItemResponse)
            ├── exception/      # OrderErrorCase (enum, ErrorCase 구현)
            └── global/         # response/CommonResponse, exception/{ApplicationException, ErrorCase, GlobalExceptionHandler}
```

**1단계에 심어둔 학습 장치** :
- 설정 env 외부화 / DB를 **DNS(`order-db`)**로 접속 / **liveness·readiness 분리** / `depends_on: service_healthy`.
- **`OrderItem`은 `productId`만 보유(FK 아님)** → 2단계 "남의 DB는 JOIN 못 하고 ID+API로만 접근"의 복선.

---

## 4. 12-factor / k8s 대비 습관 

- **설정(호스트명/포트/DSN)은 전부 환경변수로 외부화.** 코드/이미지에 하드코딩 금지. `application.yml`엔 IDE 직접 실행용 기본값만 `${VAR:-default}`로 둔다.
- **서비스는 stateless.** 상태는 DB/Kafka에만.
- **고정 IP 금지. 서비스 이름(DNS)으로만 통신**(예: DB host = `order-db`, 2단계부터 서비스 간 호출도 서비스 이름으로).
- **Actuator liveness/readiness 유지** → compose healthcheck → 나중에 k8s probe로 전환.
  - `liveness` = 프로세스 살아있나(죽으면 재시작 대상).
  - `readiness` = 요청 받을 준비 됐나. **`db`를 포함**시켜 DB가 죽으면 readiness가 DOWN → "트래픽 받지 마" 신호. (2단계 "의존 대상 죽으면 나도 못 받음"의 토대)
- **KST 습관**: JDBC URL에 `serverTimezone=Asia/Seoul` 유지. 

---

## 5. 빌드 / 실행 / 테스트

전체 스택은 레포 루트에서, Gradle 명령은 각 서비스 디렉터리(현재 `order-service/`)에서.

```bash
cp .env.example .env            # 최초 1회: env 파일 생성 (gitignore됨)
docker compose up --build       # 빌드 + 기동 (order-db가 healthy해진 뒤 order-service가 뜸)
docker compose ps
docker compose down             # 컨테이너 제거 (DB 볼륨은 유지)
docker compose down -v          # 볼륨까지 삭제 (DB 데이터 초기화)

cd order-service
./gradlew build                 # 컴파일 + 테스트 + bootJar
./gradlew bootJar -x test       # jar만, 테스트 스킵 (Dockerfile이 하는 것)
./gradlew test                  # 전체 테스트 (JUnit 5)
./gradlew test --tests 'com.commerce.order.service.OrderServiceTest'          # 단일 클래스
./gradlew test --tests 'com.commerce.order.service.OrderServiceTest.success'  # 단일 메서드
./gradlew bootRun               # 로컬 단독 실행 (application.yml 기본값 → localhost:3306 MySQL)
```

### 헬스 / API 확인

```bash
curl http://localhost:8080/actuator/health/liveness     
curl http://localhost:8080/actuator/health/readiness    
curl -X POST http://localhost:8080/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productId":100,"productName":"키보드","unitPrice":30000,"quantity":2}]}'
curl http://localhost:8080/api/v1/orders/1     
# Swagger UI: http://localhost:8080/swagger-ui.html
```

**직접 관찰할 것**: `docker compose stop order-db` → readiness가 DOWN으로 바뀌는지 확인 

---

## 6. 서비스 내부 구조 (DDD, 서비스마다 동일)

각 서비스는 독립 Gradle 프로젝트 + 독립 Dockerfile + 독립 DB를 가진다. 패키지는 `com.commerce.{service}` 하위에 레이어드:

```
domain/        # 애그리거트 루트 + 엔티티 + enum. 비즈니스 규칙의 집.
repository/     # Spring Data JPA
service/        # 트랜잭션 경계, 도메인 오케스트레이션
controller/     # REST 엔드포인트
dto/            # record 요청/응답
global/         # 횡단 관심사 (exception 등). 서비스 간 호출 추가되면 client/config도 여기.
```

- **애그리거트 경계를 지킨다.** 예: `Order`가 애그리거트 루트이고 `OrderItem`은 `Order`를 통해서만 생성/추가된다(`Order.create()` + `order.addItem(...)`). 외부에서 `OrderItem`을 직접 `new` 하지 않는다.

---

## 7. 엔티티 / JPA 룰

### 7.1 기본 골격 (필수)

```java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // JPA 전용
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Order(Long customerId) { ... }            // private 생성자
    public static Order create(Long customerId) { ... } // 정적 팩토리
}
```

- **`@NoArgsConstructor(PROTECTED)` + private 생성자 + `public static create(...)` 정적 팩토리가 표준.** public 생성자 금지.
- **Setter 금지.** 상태 변경은 의미 있는 도메인 메서드로(`addItem`, `confirm`, `cancel`, `markPaid` 등). 불변식(예: `totalAmount`)은 도메인 메서드 안에서 갱신한다.
- **Enum 컬럼은 `@Enumerated(EnumType.STRING)`** + `@Column(length = N)`. **ORDINAL 금지**(순서 바뀌면 데이터 깨짐).
- **관계는 `@ManyToOne(fetch = LAZY)` 기본**, EAGER 금지. `@OneToMany`는 부모를 통해서만 CRUD되는 sub-aggregate일 때 `cascade = ALL + orphanRemoval = true`(현재 `Order.items` 패턴).
- **`Long xxxId` 직접 필드**(FK 아님)는 **다른 서비스 소유 데이터를 참조**하거나 JOIN을 의도적으로 회피할 때 쓴다. 주석으로 의도를 명시한다(예: `OrderItem.productId`는 product-service 소유 → §13).
- 학습용이라 `ddl-auto: update`. 운영이라면 `validate` + 마이그레이션 도구(Flyway 등) — **4단계 이후에 도입**.

### 7.2 공통 시간 필드

지금은 `Order`가 `createdAt`을 직접 들고 있다. **두 번째 엔티티가 생겨 `createdAt/updatedAt`이 반복되면** 그때 `BaseTime`(@MappedSuperclass + JPA Auditing)을 추출한다 — 지금 미리 만들지 않는다(YAGNI).

---

## 8. Service 룰

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)        // 클래스 레벨 readOnly
public class OrderService {

    private final OrderRepository orderRepository;

    @Transactional                     // 쓰기 메서드에만 명시
    public OrderResponse createOrder(CreateOrderRequest request) { ... }
}
```

- 생성자 주입은 **`@RequiredArgsConstructor`**. `@Autowired` 필드 주입 금지.
- 클래스 레벨 `@Transactional(readOnly = true)` + **쓰기 메서드만 `@Transactional` 오버라이드.** (현재 `OrderService`가 이 패턴.)
- **비즈니스 예외는 `ApplicationException.from(XxxErrorCase.YYY)`로 던진다**(§11). 예: `findById(...).orElseThrow(() -> ApplicationException.from(OrderErrorCase.ORDER_NOT_FOUND))`.
- **2단계 이후 — 외부 서비스 동기 호출 주의**: 트랜잭션 경계 안에서 다른 서비스를 동기 호출하면 그 호출이 트랜잭션을 길게 잡는다. "왜 이게 문제인지"를 체감하는 게 2단계 목표이므로, **처음엔 일부러 단순하게 호출해 고통을 관찰**하고, 그 다음에 타임아웃/실패 전파/경계 분리를 다룬다.

---

## 9. Controller 룰 

```java
@RestController
@RequestMapping("/api/v1/orders")
@Validated
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @Operation(summary = "주문 생성", description = "고객 ID와 주문 항목 목록으로 새 주문을 생성한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "주문 생성 성공",
                    content = @Content(schema = @Schema(implementation = CommonResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 (필수 값 누락/유효성 위반)",
                    content = @Content(schema = @Schema(implementation = CommonResponse.class)))
    })
    public CommonResponse<OrderResponse> createOrder(@RequestBody @Valid CreateOrderRequest request) {
        return CommonResponse.success(orderService.createOrder(request));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "주문 단건 조회", description = "주문 ID로 주문 상세를 조회한다.")
    @ApiResponses({ /* 200 / 404 ... */ })
    public CommonResponse<OrderResponse> getOrder(
            @Parameter(description = "조회할 주문 ID", example = "1", required = true)
            @Positive @PathVariable Long orderId) {
        return CommonResponse.success(orderService.getOrder(orderId));
    }
}
```

- **모든 응답은 `CommonResponse<T>` 봉투로 감싼다.** 컨트롤러는 `ResponseEntity`가 아니라 `CommonResponse.success(...)`를 반환(성공은 HTTP 200). 에러 상태/바디는 `GlobalExceptionHandler`가 `ErrorCase`로부터 만든다(§11).
- **URL prefix**: `/api/v1/{resource}` (예: `/api/v1/orders`). 버전(`v1`) 포함, 서비스마다 자기 리소스 prefix.
- **모든 메서드에 Swagger `@Operation` + `@ApiResponses` 부착**(특히 4xx). `@Schema(implementation = CommonResponse.class)`. Swagger UI: `http://localhost:8080/swagger-ui.html`.
- `@Validated`(클래스) + `@Valid @RequestBody` + `@Positive @PathVariable`로 Bean Validation 적극 사용. 본문 검증 실패 → 400(`MethodArgumentNotValidException`), 파라미터 제약 위반 → 400(`ConstraintViolationException`) 모두 핸들러가 잡는다.

---

## 10. DTO 룰 (요청=record / 응답=@Builder 클래스)

- **요청 DTO = Java `record`** (`CreateOrderRequest`, `OrderLineRequest`). 컴포넌트에 Bean Validation 부착(`@NotNull`, `@NotEmpty`, `@Positive`, `@Valid`(중첩) 등). 접근은 `request.customerId()`.
- **응답 DTO = `@Getter @Builder` 클래스** (`OrderResponse`, `OrderItemResponse`). **정적 팩토리 `from(Entity)`**로 엔티티 → DTO 변환. 엔티티를 컨트롤러로 그대로 노출하지 않는다. 접근은 `response.getOrderId()`.
  ```java
  @Getter @Builder
  public class OrderResponse {
      private final Long orderId;
      // ...
      public static OrderResponse from(Order order) { return OrderResponse.builder()...build(); }
  }
  ```
- 명명: `{Resource}{Action}Request` / `{Resource}{Action}Response`. 한 줄(라인) 단위 요청은 `{Resource}LineRequest`.
- **테스트 fixture**: 요청 record는 생성자 그대로, 응답 클래스는 빌더로 만든다(§14.4). record라 `ReflectionTestUtils`는 불필요.

---

## 11. ErrorCase / Exception 룰 

**개별 예외 클래스를 만들지 않는다.** 단일 `ApplicationException` + 도메인별 `ErrorCase` enum 구조.

- **`global/exception/ErrorCase`**(인터페이스): `getHttpStatus()` / `getCode()` / `getMessage()`.
- **`global/exception/ApplicationException`**: `ErrorCase`를 들고 있는 단일 예외. **`ApplicationException.from(errorCase)`** 정적 팩토리로만 생성·던진다.
- **`{domain}/exception/{Domain}ErrorCase`**(enum, `ErrorCase` 구현): 도메인마다 자기 enum. 코드 형식 `{DOMAIN}_{3자리}`.
  ```java
  @Getter @RequiredArgsConstructor
  public enum OrderErrorCase implements ErrorCase {
      ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_001", "존재하지 않는 주문입니다.");
      private final HttpStatus httpStatus; private final String code; private final String message;
  }
  ```
- **던지기**: `throw ApplicationException.from(OrderErrorCase.ORDER_NOT_FOUND);` (서비스에서 `orElseThrow(() -> ApplicationException.from(...))`).
- **`global/exception/GlobalExceptionHandler`(`@RestControllerAdvice`)**가 일괄 변환 → 바디는 항상 `CommonResponse.error(code, message)`(§9 봉투와 통일). 현재 매핑: `ApplicationException` → ErrorCase의 상태, `MethodArgumentNotValidException`/`ConstraintViolationException` → 400.
- 새 에러는 **해당 도메인 `ErrorCase` enum에 한 줄 추가**가 전부(핸들러 수정 불필요). 상태 코드는 의미에 맞게(중복/충돌 409, 잘못된 요청 400, 못 찾음 404).

---

## 12. Repository 룰

- **Spring Data JPA.** 파생 쿼리 명명(`findByXxx`, `existsByXxx`)과 underscore navigation(`findByOrder_Id`) 사용 OK.
- 복잡한 쿼리는 `@Query` JPQL. N+1이 보이면 `@EntityGraph(attributePaths = {...})`로 명시적 해결.
- **재고/잔여 차감(3~4단계 inventory)** 같은 경쟁 자원은 `find → set → save` 금지. **Atomic UPDATE**(`@Modifying @Query("UPDATE ... SET remaining = remaining - :n WHERE id = :id AND remaining >= :n")`) 후 반환값 0이면 비즈니스 에러. (동시성 고통을 다룰 때 이 패턴을 도입한다.)

---

## 13. MSA 경계 룰 

> **이 섹션이 곧 커리큘럼이다.** 새 서비스를 추가할 때 아래를 의식적으로 지키고, "왜"를 매번 설명한다.

1. **DB per service.** 각 서비스는 **자기 DB만** 소유·접근한다. 다른 서비스의 테이블에 직접 붙거나 JOIN하지 않는다. compose에서도 DB는 서비스마다 따로(`order-db`, `product-db`, ...).
2. **남의 데이터는 ID + API로만.** 다른 서비스 소유 엔티티는 FK가 아니라 **ID 필드 + REST(또는 이벤트) 호출**로 참조한다(현재 `OrderItem.productId`가 그 복선).
3. **서비스 간 통신은 DNS(서비스 이름)으로.** 고정 IP 금지.
4. **동기 → 비동기 진화 순서를 지킨다.**
   - 2단계: 먼저 **REST 동기 호출**로 짜서 결합도/장애 전파(타임아웃, 의존 서비스 다운 시 나도 실패)를 **직접 겪는다.**
   - 3단계: 그 고통을 느낀 호출 **하나만** Kafka 이벤트로 전환한다(전부 한 번에 바꾸지 않는다).
   - 4단계: 이벤트가 늘며 생기는 정합성 문제를 **Saga(choreography vs orchestration) + Outbox + eventual consistency**로 다룬다.
5. **새 서비스 추가 체크리스트**: 새 모듈 디렉터리(`{name}-service/`) + 자체 `build.gradle`/`Dockerfile` + `docker-compose.yml`에 서비스 + 전용 DB + healthcheck + `depends_on: service_healthy` + env 외부화. 위 §1.2처럼 **한 조각씩**.

---

## 14. 테스트 룰 (fixture 기반 — service & controller)

작성자는 **fixture 기반으로 service·controller까지** 테스트를 짠다. 아래가 표준. (현재 `src/test`는 비어 있으니 첫 테스트부터 이 형식으로.)

### 14.1 Service 단위 테스트 골격

```java
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)     // 공통 stub 패턴과 충돌 방지 — STRICT 금지
@DisplayName("OrderService 단위 테스트")
class OrderServiceTest {

    @InjectMocks private OrderService orderService;    // 최상단
    @Mock private OrderRepository orderRepository;

    @Nested
    @DisplayName("createOrder")                        // 대상 메서드명 그대로
    class CreateOrder {

        @Test
        @DisplayName("성공 - 항목 2개로 주문 생성, totalAmount 합산")
        void success() {
            CreateOrderRequest request = OrderRequestFixture.defaultCreateRequest();
            given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));

            OrderResponse response = orderService.createOrder(request);

            assertThat(response.getTotalAmount()).isEqualTo(75000L);   // 응답=클래스 → getter
            then(orderRepository).should().save(any(Order.class));
        }

        @Test
        @DisplayName("실패 - 존재하지 않는 orderId → ApplicationException(ORDER_NOT_FOUND)")
        void notFound() {
            given(orderRepository.findById(99L)).willReturn(Optional.empty());
            assertThatThrownBy(() -> orderService.getOrder(99L))
                    .isInstanceOf(ApplicationException.class)
                    .extracting("errorCase").isEqualTo(OrderErrorCase.ORDER_NOT_FOUND);
        }
    }
}
```

**필수 룰**
- 클래스 3종 세트 고정: `@ExtendWith(MockitoExtension.class)` + `@MockitoSettings(strictness = LENIENT)` + `@DisplayName("Xxx 단위 테스트")`. **STRICT 금지.**
- **`@Nested`로 메서드 단위 분할.** `@Nested @DisplayName`은 대상 **메서드명 그대로**(`createOrder`), 클래스명은 PascalCase(`CreateOrder`).
- `@Test @DisplayName`은 **`성공 - <조건>, <부수효과>` / `실패 - <조건> → <예외명>` / `경계값 - <조건>`** 형식. 메서드명은 `snake_case` 짧게.
- **`@Test` 하나에 여러 시나리오(성공·실패 섞기) 금지.**

### 14.2 Assertion / Mocking 스타일 

- **AssertJ만** 사용. JUnit `assertEquals`/`assertTrue` 금지.
  ```java
  assertThat(response.getItems()).hasSize(2).extracting(OrderItemResponse::getProductName).contains("키보드");
  assertThatThrownBy(() -> service.getOrder(99L)).isInstanceOf(ApplicationException.class);
  assertThatCode(() -> service.createOrder(valid)).doesNotThrowAnyException();
  ```
- **BDDMockito만** 사용. `when(...).thenReturn(...)` 금지 → `given(...).willReturn(...)`. void는 `willDoNothing().given(...)`.
- 호출 검증은 **`then(...).should(...)`**: `then(orderRepository).should().save(any());` / `then(repo).should(never()).save(any());`

### 14.3 Controller 슬라이스 테스트 골격

```java
@WebMvcTest(controllers = OrderController.class)      // 슬라이스 명시 — 단독 @WebMvcTest 금지
@DisplayName("OrderController 슬라이스 테스트")
class OrderControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private OrderService orderService;    // @MockBean(deprecated) 금지
    private static final String BASE = "/api/v1/orders";

    @Test
    @DisplayName("성공 - 주문 생성 → 200, CommonResponse.data 반환")
    void create_success() throws Exception {
        given(orderService.createOrder(any())).willReturn(OrderResponseFixture.defaultResponse());

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                {"customerId":1,"items":[{"productId":100,"productName":"키보드","unitPrice":30000,"quantity":2}]}
                """))
            .andDo(print())                            // 실패 시 디버깅용 — 모든 테스트에 부착
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))      
            .andExpect(jsonPath("$.data.orderId").value(1L));  // 실제 데이터는 data 아래
    }

    @Test
    @DisplayName("실패 - 존재하지 않는 주문 조회 → 404, code=ORDER_001")
    void get_notFound() throws Exception {
        given(orderService.getOrder(99L)).willThrow(ApplicationException.from(OrderErrorCase.ORDER_NOT_FOUND));
        mockMvc.perform(get(BASE + "/{id}", 99L))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("ORDER_001"));
    }
}
```

**필수 룰**
- **`@WebMvcTest(controllers = XxxController.class)`** — 슬라이스 명시. 전체 컨트롤러 로드 금지.
- **`@MockitoBean`**(Spring Boot 3.4+). `@MockBean`은 deprecated.
- JSON body는 **Java Text Block**(`"""`)으로. `.andDo(print())` 부착. 응답은 **`CommonResponse` 봉투**라 `$.success` / `$.code` / `$.data.*`로 검증.
- HTTP 상태는 `ErrorCase`/핸들러 매핑과 **정확히 일치**시켜 검증(404/400 등). `GlobalExceptionHandler`도 슬라이스에 로드되므로 예외 → 상태·code 매핑을 그대로 검증 가능.
- **보안/JWT가 없으므로** `@WithMockUser`/`TestSecurityConfig`는 지금 불필요. (게이트웨이·인증이 들어오는 단계에서 추가한다.)

### 14.4 Fixture 클래스

위치: `src/test/java/com/commerce/order/fixture/`. **요청(record)은 생성자 그대로, 응답(@Builder 클래스)은 빌더로** 기본값을 만든다.

```java
public class OrderRequestFixture {                     // 요청 record fixture
    public static CreateOrderRequest defaultCreateRequest() {
        return new CreateOrderRequest(1L, List.of(
                new OrderLineRequest(100L, "키보드", 30000L, 2),
                new OrderLineRequest(200L, "마우스", 15000L, 1)));
    }
    public static CreateOrderRequest createRequest(Long customerId, List<OrderLineRequest> items) {
        return new CreateOrderRequest(customerId, items);
    }
}

public class OrderResponseFixture {                    // 응답 @Builder fixture (컨트롤러 슬라이스 stub용)
    public static OrderResponse defaultResponse() {
        return OrderResponse.builder()
                .orderId(1L).customerId(1L).status(OrderStatus.CREATED).totalAmount(75000L)
                .items(List.of(OrderItemResponse.builder()
                        .productId(100L).productName("키보드").unitPrice(30000L).quantity(2).lineTotal(60000L).build()))
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
    }
}
```

- 정적 메서드만(인스턴스화 금지). **`defaultXxx()` 헬퍼 필수**(테스트 가독성 결정적). 실패 케이스용 헬퍼(`requestWithEmptyItems()` 등)도 같이 둔다.
- 엔티티 fixture가 필요하면 정적 팩토리(`Order.create(...)` + `addItem`)로 만든다. 여러 테스트가 같은 트리를 쓰면 `OrderEntityFixture`로 추출.

### 14.5 테스트 안티패턴 (금지)

- ❌ `@MockBean` → `@MockitoBean` · ❌ `when().thenReturn()` → `given().willReturn()`
- ❌ JUnit `assertEquals`/`assertTrue` → AssertJ · ❌ 단독 `@WebMvcTest`(controllers 명시)
- ❌ `@SpringBootTest`로 슬라이스 가능한 걸 작성(느림) · ❌ `@Test` 하나에 성공·실패 혼합
- ❌ `@MockitoSettings(STRICT_STUBS)`(LENIENT 표준)

---

## 15. Git 워크플로 

> 이 레포는 아직 git 저장소가 아닐 수 있다. Claude는 **파일 작성·검증까지만** 하고, 아래처럼 **사용자가 복붙해서 실행할 명령어를 정리해 제시**한다. 직접 실행하지 않는다.

작업을 마치면 이런 형식으로 끝낸다:

```bash
# 예시 — 사용자가 직접 실행
git add order-service/src/main/java/com/commerce/order/...
git commit -m "feat(order): ..."
git push
```

- 커밋 메시지·브랜치 전략은 사용자 판단에 맡긴다(원하면 제안만).
- 원격 레포 생성(`gh repo create`)도 사용자가 직접.

---

## 16. 다음 할 일 — 2단계 

**목표**: product/inventory 서비스 + 두 번째 DB를 추가하고, order-service가 **주문 생성 시 그 서비스를 REST로 동기 호출**(재고 확인/차감)하게 만든다. 그래서 다음을 직접 체감한다:
- **"남의 DB는 JOIN 못 하고 ID+API로만 접근한다"** — `OrderItem.productId`로 product-service를 호출.
- **"의존 서비스가 죽으면 나도 못 받는다"** — 타임아웃/실패 전파를 관찰(product-service를 `docker compose stop` 하고 주문 생성 시도).

**진행 방식**: 2단계도 **"왜 지금 두 번째 서비스가 필요한가"(1단계의 단일 서비스로는 못 느끼는 결합/장애 전파)**를 먼저 설명하고, 그 다음 한 조각씩 — ① product-service 스캐폴드 + product-db + compose 추가 → ② order → product **동기 REST 호출** 연결 → ③ 의존 서비스 다운/타임아웃을 일부러 일으켜 고통 관찰 → ④ (그 고통이 3단계 Kafka 전환의 동기가 된다).

---

## 17. Claude Code 작업 지침 (셀프 룰)

1. **현재 단계를 먼저 확인한다**(§3). 로드맵보다 앞서가지 않는다. 안 온 인프라를 미리 깔지 않는다.
2. **조각 추가 전 "왜 지금"을 설명한다**(§1.3). 직전 단계의 고통과 연결한다.
3. **한 턴에 한 조각.** 여러 서비스/인프라 동시 scaffold 금지.
4. **코드는 완전한 형태로.** 요약/생략 금지.
5. **신규 코드는 이 문서 룰대로**(엔티티 정적 팩토리·no setter, record DTO, 트랜잭션 골격, fixture 기반 테스트). 모호하면 현재 order-service 코드를 reference로.
6. **테스트 동반.** 새 서비스/메서드엔 §14 표준으로 service·controller 테스트를 fixture부터 만들고 시작.
7. **막히면 같이 푼다**(중복 소비, 기동 순서, 트랜잭션 경계 등). 우회가 아니라 원인 이해.
8. **git은 사용자가**(§15). 작업 끝에 실행할 명령어만 정리해 제시.
9. **MSA 경계를 어기지 않는다**(§13): DB per service, 남의 테이블 직접 접근 금지, ID+API로만.
10. **모호하거나 룰이 충돌하면 추측보다 한 번 묻는다.**