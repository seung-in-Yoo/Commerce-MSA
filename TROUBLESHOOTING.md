# 트러블슈팅 기록 — commerce-msa

구현하면서 실제로 막혔던 지점과 **증상 → 원인 → 해결 → 교훈**을 기록 

## 목차

| ID | 날짜 | 단계 | 한 줄 요약                                                                        |
|---|---|---|-------------------------------------------------------------------------------|
| [TS-11](#ts-11--kind-load-docker-image가-content-digest--not-found로-실패) | 2026-06-24 | step8 | `kind load docker-image`가 `content digest ... not found`로 실패 — Docker Desktop containerd 이미지 스토어 + 멀티플랫폼 이미지 |
| [TS-10](#ts-10--k8s에서-product-service-crashloopbackoff--no-resolvable-bootstrap-urls) | 2026-06-24 | step8 | k8s 조각2에서 product-service가 `CrashLoopBackOff` — kafka Service 미배포라 `@KafkaListener` 컨슈머가 부팅 끝에 `kafka:9092` DNS resolve 실패(`No resolvable bootstrap urls`) |
| [TS-9](#ts-9--커스텀-producerfactory에-native-producer-metric이-안-나온다) | 2026-06-24 | step7 | `actuator/prometheus`에 `kafka_producer_*`가 하나도 없음 — 커스텀 ProducerFactory엔 KafkaClientMetrics가 자동으로 안 붙음 |
| [TS-8](#ts-8--grafana-datasource-loki-was-not-found--프로비저닝은-부팅-시-한-번만-읽는다) | 2026-06-18 | step6 | 대시보드에 `Datasource loki was not found` — datasource.yml에 Loki 추가했지만 grafana를 재기동 안 해 미반영 |
| [TS-7](#ts-7--outboxstore-and-forward-경계가-분산추적-trace를-끊는다) | 2026-06-18 | step5b | Kafka 추적은 켰는데 사가가 한 trace로 안 묶임 — Outbox 릴레이가 다른 스레드/나중에 발행해 trace 단절 |
| [TS-6](#ts-6--게이트웨이reactive-로그에-traceid가-안-찍힌다) | 2026-06-18 | step5b | reactive 게이트웨이 로그의 traceId 빈칸 — context를 조립 시점에 읽음 + 자동 컨텍스트 전파 미활성 |
| [TS-5](#ts-5--게이트웨이-actuatorgatewayroutes-404) | 2026-06-18 | step5a | `/actuator/gateway/routes` 404 — `exposure.include`만 하고 엔드포인트 `access`를 안 열었음 |
| [TS-4](#ts-4--멀티타입-컨슈머에서-단일-valuedefaulttype의-한계) | 2026-06-16 | step4b | 두 토픽 구독 컨슈머에서 `containerFactory` 설정 없음 → 단일 default 타입이 다른 타입을 못 받고 깨짐        |
| [TS-3](#ts-3--새-결제-서비스가-과거-이벤트를-재생해-유령-결제-생성) | 2026-06-15 | step4b | 새 payment가 `earliest`로 과거 이벤트 재생 → 유령 결제(amount=0) + 중복 소비                    |
| [TS-2](#ts-2--주문-생성-시-column-product_name-cannot-be-null-http-500) | 2026-06-12 | step3b | 주문 생성 시 `Column 'product_name' cannot be null` (HTTP 500)                     |
| [TS-1](#ts-1--kafka-브로커-기동-실패-kafka_listeners에-0000) | 2026-06-11 | step3a | Kafka 브로커 기동 실패 (`KAFKA_LISTENERS`에 `0.0.0.0`)                                |

---

## TS-11 — `kind load docker-image`가 `content digest ... not found`로 실패

- **날짜**: 2026-06-24
- **단계**: step8 (쿠버네티스 — 조각 kafka 이미지 적재)

### 증상

kafka(`apache/kafka:3.9.0`)를 kind 노드에 적재하려는데 실패했다:

```
$ kind load docker-image apache/kafka:3.9.0 --name commerce
Image: "apache/kafka:3.9.0" with ID "sha256:fbc7d..." not yet present on node "commerce-worker2", loading...
ERROR: failed to load image: command "docker exec ... ctr ... images import ..." failed with error: exit status 1
Command Output: ctr: content digest sha256:515a27c1...: not found
```

### 원인 — containerd 이미지 스토어 + 멀티플랫폼 매니페스트

- Docker Desktop의 **containerd 이미지 스토어**가 켜져 있으면(`docker images` 출력에 `DISK USAGE / CONTENT SIZE` 컬럼이 보이는 게 신호) `docker save`가 **멀티플랫폼 매니페스트**를 내보내는데, 현재 머신에 없는 플랫폼의 블롭(digest)까지 참조한다. `kind load`가 내부적으로 쓰는 `ctr images import`가 그 빠진 digest를 찾다 실패한다
- **로컬 단일 빌드 이미지(`commerce-msa-*`)는 단일 플랫폼이라 같은 경로로도 정상 로드된다** — 실제로 order/payment 이미지는 문제없이 들어갔다. 멀티아치 공개 이미지에서만 터진 것.

### 해결 — 공개 이미지는 애초에 load가 불필요

- kafka는 **Docker Hub 공개 이미지**라 노드가 직접 pull할 수 있다. `kind load`를 건너뛰고 그냥 apply하면 `imagePullPolicy: IfNotPresent`가 노드에 없을 때 Hub에서 pull한다:

```yaml
# kafka.yaml
image: apache/kafka:3.9.0
imagePullPolicy: IfNotPresent   # 노드에 없으면 Hub에서 pull
```

```
$ kubectl apply -f k8s/manifests/kafka.yaml
$ kubectl get pods -n commerce   # kafka-0 가 ContainerCreating → Running
```

- 인터넷 차단 환경이면 우회책: `docker save apache/kafka:3.9.0 -o /tmp/kafka.tar && kind load image-archive /tmp/kafka.tar --name commerce`, 또는 노드 안에서 `docker exec commerce-worker crictl pull apache/kafka:3.9.0`

### 교훈

- **이미지 출처가 적재 절차를 가른다.** 로컬 빌드 이미지(레지스트리에 없음)는 `kind load`가 필수지만, 공개 이미지는 노드가 pull하면 되니 load가 오히려 불필요 — 그리고 멀티아치 공개 이미지는 containerd 스토어에서 load가 깨질 수 있다
- **에러 메시지의 진짜 원인은 도구 체인 깊은 곳에 있을 수 있다.** `ctr: content digest not found`는 kind/kafka의 문제가 아니라 Docker Desktop의 이미지 스토어 설정에서 비롯됐다 — 같은 명령이 다른 이미지(단일아치)엔 멀쩡했다는 점이 단서

---

## TS-10 — k8s에서 product-service `CrashLoopBackOff` (`No resolvable bootstrap urls`)

- **날짜**: 2026-06-24
- **단계**: step8 (쿠버네티스 — 조각2 product-service Deployment)

### 증상

조각1(product-db StatefulSet)이 `1/1 Running`인 상태에서 조각2로 product-service Deployment를 apply했더니
파드가 뜨자마자 죽기를 반복했다:

```
$ kubectl get pods -n commerce
NAME                               READY   STATUS             RESTARTS        AGE
product-db-0                       1/1     Running            0               35m
product-service-68cd499f8b-shmqz   0/1     CrashLoopBackOff   10 (4m11s ago)  31m
```

`kubectl logs --previous`의 마지막 stack:

```
Caused by: org.apache.kafka.common.KafkaException: Failed to construct kafka consumer
  ... ClassicKafkaConsumer.<init> ...
  ... KafkaMessageListenerContainer.doStart ...
  ... KafkaListenerEndpointRegistry.start ...
  ... DefaultLifecycleProcessor.doStart ...
Caused by: org.apache.kafka.common.config.ConfigException:
           No resolvable bootstrap urls given in bootstrap.servers
```

그 위에 비치명 ERROR도 한 줄 찍혀 있었다(아래 참고):

```
ERROR ... o.springframework.kafka.core.KafkaAdmin : Could not create admin
Caused by: ... ConfigException: No resolvable bootstrap urls ...
```

### 원인 — compose가 숨겨준 결합이 k8s에서 터졌다

- product-service의 `StockCommandListener`는 `@KafkaListener(topics = "stock-commands")` 컨슈머다.
- 스프링 부팅 **마지막 단계**에서 `KafkaListenerEndpointRegistry`(SmartLifecycle)가 이 리스너 컨테이너를 `start` → 컨슈머를 **생성**하면서 `bootstrap.servers = kafka:9092`를 **DNS resolve** 시도한다.
- 그런데 조각2 시점의 클러스터엔 **`kafka` Service가 아직 없다**(kafka는 뒤 조각에서 배포). DNS 자체가 없어 `No resolvable bootstrap urls` → 컨슈머 생성 실패 → **context refresh 실패** → 프로세스 종료 → `CrashLoopBackOff`.
- **왜 compose에선 안 터졌나**: docker-compose에선 kafka 컨테이너가 늘 떠 있어 `kafka:9092` DNS가 항상 resolve됐다. "앱이 kafka에 부팅 시점부터 묶여 있다"는 **결합을 compose가 가려줬던** 것 — k8s로 옮겨 의존 Service를 하나씩 세우니 그제서야 드러났다.
- **흔한 오해 정정**: "readiness 그룹에 kafka가 없으니 브로커가 없어도 앱은 뜨고 컨슈머만 백그라운드 재시도한다"는 *브로커만 죽고 DNS는 되는* 경우에만 참이다. 지금은 **DNS 자체가 불가**라 컨슈머 생성 단계에서 hard fail — 결이 다르다.
- **앞의 `KafkaAdmin: Could not create admin` ERROR는 범인이 아니다.** `KafkaAdmin`은 `fatalIfBrokerNotAvailable` 기본값이 `false`라, 같은 DNS 실패를 만나도 **로그만 찍고 context를 죽이지 않는다.** 실제 킬러는 그 뒤의 **리스너 컨테이너 start**다(stack의 `KafkaListenerEndpointRegistry.start`로 확인).

### 해결 — 조각2 한정으로 리스너 자동 기동을 끈다

ConfigMap(`product-service-config`)에 한 줄 추가:

```yaml
data:
  # ...
  # 조각2 한정: kafka Service가 아직 없으므로 @KafkaListener를 자동 기동하지 않는다.
  SPRING_KAFKA_LISTENER_AUTO_STARTUP: "false"
```

`spring.kafka.listener.auto-startup=false`(env relaxed-binding)가 되면 `KafkaListenerEndpointRegistry`가
리스너 컨테이너를 **start하지 않는다 → 컨슈머를 아예 생성 안 함 → DNS resolve 자체가 없음 → 부팅 성공.**
(`StockCommandListener`는 커스텀 `containerFactory` 없이 디폴트 팩토리를 쓰므로 이 전역 설정이 그대로 먹는다.)

적용 후:

```
$ kubectl rollout restart deployment/product-service -n commerce
$ kubectl get pods -n commerce -l app=product-service
NAME                               READY   STATUS    RESTARTS   AGE
product-service-67d7447bb6-z75ds   1/1     Running   0          24s

# Started ProductApplication in 2.112 seconds
# readiness: {"status":"UP","components":{"db":{"status":"UP"...},"readinessState":{"status":"UP"}}}
```

`Failed to construct kafka consumer`는 사라졌고, 비치명 `KafkaAdmin: Could not create admin` ERROR 한 줄만 남는다(정상 — 안 죽임).

> **조각2는 "앱1 + DB1"로 유지**하는 게 이 단계의 목적이다. kafka를 배포하는 뒤 조각에서 이 줄을 제거(또는 `"true"`)해 컨슈머를 되살린다.

### 교훈

- **compose는 "전부 한 네트워크에 늘 떠 있음" 덕분에 부팅 시점 의존성을 가려준다.** k8s로 옮겨 Service를 한 조각씩 세우면, 숨어 있던 *부팅 순서/하드 의존*이 그대로 드러난다 — 이게 §13 "동기 결합을 직접 겪는다"의 인프라 버전.
- **같은 예외 메시지(`No resolvable bootstrap urls`)라도 "로그만 찍는 곳"과 "context를 죽이는 곳"이 다르다.** `KafkaAdmin`(비치명) vs 리스너 컨테이너 start(치명)를 stack으로 구분해야 진짜 범인을 잡는다. ERROR 레벨이라고 다 기동 실패의 원인은 아니다.
- **부팅 시점 외부 의존을 줄이는 안전장치 = `auto-startup: false`.** 의존 인프라가 아직 없거나 느린 환경에서 리스너를 늦게 켜는 건 흔한 운영 패턴(이후 `KafkaListenerEndpointRegistry`로 런타임에 start 가능).
- TS-8 교훈("설정 파일 교체 ≠ 프로세스 재적재")과 짝 — ConfigMap을 바꿔도 파드는 자동으로 다시 안 읽으므로 `rollout restart`로 새 파드를 띄워야 반영된다.

---

## TS-9 — 커스텀 ProducerFactory에 native producer metric이 안 나온다

- **날짜**: 2026-06-24
- **단계**: step7 (Kafka 성능 — 조각5 producer 튜닝 A/B 관찰)

### 증상

조각5 producer 튜닝(`linger.ms`/`batch.size`/`compression`) 효과를 보려고 부하 전
order-service `actuator/prometheus`에서 producer 지표를 찾았는데 **하나도 없었다**:

```
$ curl -s localhost:8080/actuator/prometheus | grep '^kafka_producer'
(빈 결과)
```

`spring_kafka_template_*`(Spring 관측 metric)은 있는데, 정작 보려던
`kafka_producer_batch_size_avg` / `compression_rate_avg` / `records_per_request_avg` 같은
**Kafka 클라이언트 native metric**은 전부 없었다.

### 원인 — 커스텀 factory엔 KafkaClientMetrics 자동 바인딩이 안 붙는다

- Spring Boot가 **자동 구성**하는 `ProducerFactory`에는 `KafkaClientMetrics`가 자동으로 연결돼 native client metric이 노출된다.
- 그런데 우리는 `KafkaProducerConfig`에서 튜닝 옵션을 직접 넣으려고 **`DefaultKafkaProducerFactory`를 직접 `new`** 했다.
  → auto-config 경로를 벗어나면서 **client metric 자동 바인딩도 같이 잃은** 것.
- kafka-exporter는 broker 쪽 consumer lag만 본다 — producer 배치/압축은 **client-side metric**이라 거기에도 안 잡힌다.

### 해결 — MicrometerProducerListener를 직접 등록

```java
DefaultKafkaProducerFactory<String, String> producerFactory = new DefaultKafkaProducerFactory<>(props);
producerFactory.addListener(new MicrometerProducerListener<>(meterRegistry)); // ← 직접 등록
```

빈 시그니처에 `MeterRegistry`를 주입받아 넘긴다. 재기동 후 첫 발행이 일어나면
`kafka_producer_*` 87종이 노출되고, A/B 관찰(`records_per_request_avg` 16→99 등)이 가능해졌다.

### 교훈

- **커스텀 빈을 직접 만들면 자동 구성이 얹어주던 부가 기능(여기선 계측)도 같이 사라진다.** "왜 자동으로 되던 게 안 되지"의 절반은 auto-config 경로를 벗어난 탓.
- **metric은 첫 사용 시점에 등록된다.** producer가 한 번도 send하지 않으면 `kafka_producer_*`가 아예 안 보인다 — "metric 없음"을 "계측 실패"로 오인하지 말 것(워밍업 1건 발행 후 재확인).
- producer(client-side) 지표와 broker-side(exporter) 지표는 **수집 경로가 다르다.** lag은 exporter, 배치/압축은 client metric.

---

## TS-8 — Grafana "Datasource loki was not found" — 프로비저닝은 부팅 시 한 번만 읽는다

- **날짜**: 2026-06-18
- **단계**: step6 (모니터링 — Loki+Promtail 로그 집계 조각)

### 증상

조각3에서 `datasource.yml`에 Loki/Zipkin을 추가하고 `docker compose up -d loki promtail grafana` 후
Grafana 대시보드(Logs)에 들어가니 **`Datasource loki was not found`**.

`docker compose up -d` 출력을 보면 단서가 있었다:

```
✔ Container commerce-msa-grafana-1    Running     ← Started/Recreated 가 아니라 "Running"
✔ Container commerce-msa-loki-1       Started
✔ Container commerce-msa-promtail-1   Started
```

### 원인 — 기존 컨테이너는 재기동되지 않았고, 프로비저닝은 부팅 때만 읽힌다

- Grafana는 **datasource/dashboard 프로비저닝 파일을 프로세스 시작 시점에 한 번만** 읽는다.
- grafana 컨테이너는 조각2에서 **이미 떠 있었고**, 조각3 변경(`datasource.yml`에 Loki 추가, compose에 `depends_on: loki` 추가)은
  compose가 보기에 **컨테이너를 재생성할 만큼의 스펙 변화가 아니었다** → grafana는 `Running` 상태로 그대로 유지.
- 결국 grafana는 **새 datasource.yml을 다시 읽지 않았고**, 대시보드 JSON이 참조하는 `uid: loki`가 인스턴스에 없어 "not found".

### 해결 — grafana만 재기동시켜 프로비저닝을 다시 읽게 한다

```bash
docker compose restart grafana
```

재기동 로그에서 등록 확인:

```
provisioning.datasources level=info msg="inserting datasource from configuration" name=Loki  uid=loki
provisioning.datasources level=info msg="inserting datasource from configuration" name=Zipkin uid=zipkin
```

(`restart` 대신 `docker compose up -d --force-recreate grafana`도 동일 효과.)

### 교훈

- **프로비저닝 파일을 바꿨으면 그 컨테이너를 반드시 재기동**해야 반영된다. `up -d`가 `Running`이라 찍히면 변경이 안 먹은 것 —
  `Recreated`/`Started`인지 출력을 확인하는 습관.
- 헷갈리는 부수 로그와 진짜 원인을 분리할 것: 같은 로그의 `401 invalid password`(첫 로그인 후 admin 비번 변경 탓, API 호출 건),
  `xychart already registered`, `provisioning/plugins|alerting ... no such file`은 전부 **무해한 잡음**이고 datasource 문제와 무관했다.
- 향후 k8s에선 ConfigMap을 바꿔도 파드가 자동으로 다시 안 읽는 것과 같은 결의 함정 — "설정 파일 교체 ≠ 프로세스 재적재".

---

## TS-7 — Outbox(store-and-forward) 경계가 분산추적 trace를 끊는다

- **날짜**: 2026-06-18
- **단계**: step5b (분산추적 — Kafka 전파)

### 배경

Kafka producer/consumer에 observation을 켜서(`KafkaTemplate.setObservationEnabled` / 컨테이너 `observationEnabled`)
사가(주문→결제→재고)가 trace 헤더로 이어지길 기대했다. 목표는 "주문 한 건 = Zipkin에서 한 trace".

### 증상

Kafka 추적은 분명히 동작하는데, **주문 한 건이 Zipkin에서 3개의 분리된 trace**로 쪼개졌다.

```
trace #1  gateway → order (HTTP)                                   ← 유저 요청, 여기서 끝
trace #2  outbox-relay.publish → payment-commands → payment → payment-replies → order
trace #3  outbox-relay.publish → stock-commands  → product → stock-replies  → order
```

trace #2·#3의 **루트가 `task outbox-relay.publish-pending`**(스케줄러 폴링)이고, 유저 요청(#1)과 이어지지 않았다.

### 원인 — store-and-forward가 trace context를 떨군다

order는 사가 명령을 Kafka로 직접 쏘지 않고 **Outbox 테이블에 적재**한다([[step4-plan]] 4e). 실제 발행은
별도 `@Scheduled` 릴레이가 **나중에, 다른 스레드**로 한다.

1. 유저 요청(trace A)은 outbox 행을 **DB에 쓰고 끝난다.** 발행이 일어나지 않으므로 trace A는 거기서 종료.
2. 릴레이 폴러는 **자기만의 trace(스케줄러)**로 깨어나 행을 읽어 발행한다. KafkaTemplate observation은 그 시점의
   현재 trace(=스케줄러)를 헤더에 실으므로, 컨슈머들은 **스케줄러 trace**를 이어받는다 → 유저 요청과 단절.
3. order가 결제 응답을 받아(스케줄러 trace) 다음 명령을 또 outbox에 쓰면, 그것도 **다음 폴링의 또 다른 trace**로
   발행된다 → 사가가 폴링 경계마다 조각난다.

즉 **observation을 켠 것만으로는 부족하다.** 비동기 store-and-forward(아웃박스/큐/배치)는 "지금 이 스레드의 trace"를
잃어버리는 경계다.

### 해결 — 적재 시점의 trace context를 행에 저장하고, 발행 시 복원

(1) 적재(`SagaCommandPublisher`): 현재 trace를 전파 carrier로 추출해 outbox 행에 함께 저장.

```java
private String captureTraceContext() {
    Span span = tracer.currentSpan();
    if (span == null) return null;
    Map<String, String> carrier = new HashMap<>();
    propagator.inject(span.context(), carrier, (c, k, v) -> c.put(k, v));   // 전파 포맷 무관
    return serialize(carrier);                                              // 행의 trace_context 컬럼에 저장
}
```

(2) 발행(`OutboxRelay`): 저장한 context를 복원한 span scope **안에서** 발행 → KafkaTemplate observation이
**원래 trace**를 부모로 헤더에 싣는다.

```java
private void send(OutboxMessage message) {
    Span restored = restoreSpan(message);                  // propagator.extract(carrier).start()
    if (restored == null) { doSend(message); return; }
    try (Tracer.SpanInScope ignored = tracer.withSpan(restored)) {
        doSend(message);                                   // 이 안에서 발행 -> 원래 trace로 전파
    } finally {
        restored.end();
    }
}
```

확인 — 주문 한 건이 **한 trace(13 span, 4개 서비스)**로 이어진다:

```
gateway POST → order POST → outbox-relay.publish → payment-commands send → payment receive
            → payment-replies send → order receive → outbox-relay.publish → stock-commands send
            → product receive → stock-replies send → order receive
```

### 교훈

- **분산추적의 진짜 적은 비동기 경계다.** HTTP·동기 Kafka는 observation만 켜면 알아서 전파되지만,
  **Outbox/큐/스케줄러처럼 "나중에 다른 스레드가 발행"하는 store-and-forward는 trace를 떨군다.**
- **컨텍스트를 데이터와 함께 저장하라.** 메시지를 영속화할 때 trace context(carrier)도 같이 적재하고,
  발행 시 복원해 scope를 열면 끊긴 trace가 다시 이어진다. (멱등 키 `messageId`를 행에 저장한 것과 같은 발상)
- **`Propagator` 추상화로 포맷 의존을 피한다.** traceparent 문자열을 손으로 만들지 말고 inject/extract를 쓰면
  B3/W3C 어느 포맷이든 대칭으로 동작한다.

---

## TS-6 — 게이트웨이(reactive) 로그에 traceId가 안 찍힌다

- **날짜**: 2026-06-18
- **단계**: step5b (분산추적 — Micrometer Tracing + Zipkin)

### 배경

분산추적을 켠 뒤, 게이트웨이의 요청 로깅 필터(`RequestLoggingGlobalFilter`)가 남기는 `[gateway] ...` 줄에도
traceId가 함께 찍히길 기대했다. servlet 서비스(order/product/payment)는 자동으로 잘 찍혔다.

### 증상

게이트웨이 로그만 trace 상관관계 필드가 **빈칸**이었다. (Boot가 자동으로 붙이는 `[traceId,spanId]` 자리)

```
... [                                                 ] c.c.g.filter.RequestLoggingGlobalFilter : [gateway] GET /api/v1/products/1 -> route=product-service status=200 OK
```

명시적으로 `tracer.currentSpan()`을 읽어 찍어봐도 `traceId=no-trace`가 나왔다 — **현재 span이 null**이었다.
반면 같은 요청의 **Zipkin trace와 다운스트림(order) 로그에는 traceId가 멀쩡히** 있었다(즉 추적 자체는 동작).

### 원인 — reactive에선 trace context가 "조립 시점"이 아니라 "구독 시점"에 있다 + 자동 전파 미활성

1. 게이트웨이는 WebFlux(reactive)다. 필터의 `filter()` 메서드 본문은 `Mono`를 **조립(assemble)** 할 뿐이고,
   실제 실행은 나중에 **구독(subscribe)** 시점에 다른 스레드에서 일어난다. trace context(ThreadLocal)는
   **구독 시점에만** 세팅되므로, 조립 시점에 `tracer.currentSpan()`을 읽으면 null이다.
2. 게다가 reactive 콜백(`then(...)`) 안에서 읽더라도, **Reactor 자동 컨텍스트 전파**가 꺼져 있으면
   reactor context에 있는 trace를 ThreadLocal로 복원해주지 않아 역시 null/빈칸이 된다.

### 해결 — 콜백 안에서 읽기 + 자동 컨텍스트 전파 활성화

(1) traceId 읽기를 `Mono` 조립 시점이 아니라 **reactive 콜백 안**으로 옮긴다:

```java
return chain.filter(exchange).then(Mono.fromRunnable(() -> {
    Span span = tracer.currentSpan();                       // 콜백 안에서 읽는다
    String traceId = (span != null) ? span.context().traceId() : "no-trace";
    log.info("[gateway] traceId={} {} {} -> route={} status={} ...", traceId, ...);
}));
```

(2) 시작 시 **Reactor 자동 컨텍스트 전파**를 켠다(ThreadLocal 복원):

```java
public static void main(String[] args) {
    Hooks.enableAutomaticContextPropagation();   // reactor context <-> ThreadLocal(MDC/trace) 자동 복원
    SpringApplication.run(GatewayApplication.class, args);
}
```

확인 — Boot 자동 MDC 패턴과 명시 로그 둘 다 채워진다:

```
... [6a335c8b323730c4e290b51df8423243-e290b51df8423243] ... : [gateway] traceId=6a335c8b323730c4e290b51df8423243 GET /api/v1/products/1 -> route=product-service status=200 OK (52ms)
```

### 교훈

- **reactive에서 trace/MDC는 ThreadLocal이 아니라 Reactor Context에 산다.** servlet의 ThreadLocal 감각으로
  "그냥 현재 span 읽으면 되겠지" 하면 null이 나온다. 값은 **콜백 안에서** 읽고, **자동 컨텍스트 전파**를 켜야 한다.
- **추적이 깨진 게 아니라 '내 로그에서만' 안 보였다.** Zipkin·다운스트림 로그엔 멀쩡했다 — 증상 범위를 좁히면
  (전체 추적 실패가 아니라 게이트웨이 자기 로그 한정) 원인이 전파/스레딩 문제로 좁혀진다.

---

## TS-5 — 게이트웨이 `actuator/gateway/routes` 404 — 노출만 하고 access를 안 열었다

- **날짜**: 2026-06-18
- **단계**: step5a (API Gateway 도입 — 단일 진입점 + 경로 라우팅)

### 배경 — 등록된 라우트 목록을 확인하려 했다

`gateway-service`(Spring Cloud Gateway 2025.0.0, `:8000`)를 띄우고, 라우팅이 의도대로 등록됐는지
`/actuator/gateway/routes`로 확인하려 했다. `application.yml`에서 분명히 노출은 해 둔 상태였다:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,gateway   # gateway 노출함
```

### 증상

```
$ curl http://localhost:8000/actuator/gateway/routes
{"status":404,"error":"Not Found","path":"/actuator/gateway/routes", ...}
```

`/actuator/health`는 200으로 잘 뜨는데 `gateway`만 404. exposure에 분명히 넣었는데도 안 보였다.

### 원인 — 노출(exposure) ≠ 접근 허용(access)

`gateway`처럼 라우트·필터 같은 **운영 정보를 드러내는 엔드포인트**는 `exposure.include`로 HTTP에 노출하는 것만으로는 부족하다. 엔드포인트의 **access**가 기본적으로 닫혀 있어서, 노출돼 있어도 접근이 막히면 404로 나간다.

### 해결

`application.yml`에 게이트웨이 엔드포인트 access를 명시적으로 연다:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,gateway
  endpoint:
    gateway:
      access: unrestricted        # 노출(include)만으로는 부족 — access를 열어야 200
```

확인:

```
$ curl http://localhost:8000/actuator/gateway/routes        # HTTP 200
order-service   -> http://order-service:8080
product-service -> http://product-service:8080
payment-service -> http://payment-service:8080
```

### 교훈

- **노출(`exposure.include`) ≠ 접근 허용(`access`).** actuator 엔드포인트는 두 관문을 다 통과해야 보인다. include만 보고 "열었다"고 착각하면, 다른 엔드포인트(`health`)는 멀쩡히 뜨는데 특정 엔드포인트만 404라 원인을 잡기 어렵다.
- **Boot 3.5부터 `enabled` → `access`.** `management.endpoint.*.enabled`는 deprecated. `access`(`none`/`read-only`/`unrestricted`)로 표현한다.

---

## TS-4 — 멀티타입 컨슈머에서 단일 `value.default.type`의 한계 

- **날짜**: 2026-06-16
- **단계**: step4b (payment 추가, 주문→결제→재고 3-step Saga)

### 배경 — payment는 타입이 다른 두 토픽을 구독한다

```
OrderEventListener  : order-events   → OrderCreatedEvent  기대 (결제 시도)
StockResultListener : product-events → StockProcessedEvent 기대 (재고 실패면 환불)
```

그런데 `application.yml`의 컨슈머 설정은 역직렬화 타입을 **딱 하나만** 지정할 수 있다:

```yaml
spring.json.value.default.type: com.commerce.payment.messaging.event.OrderCreatedEvent
```

### 증상

**정상 주문 하나**(한도 이하 + 재고 충분)를 넣음 -> 결제는 멀쩡히 승인됐는데, 재고 결과를 받는 순간 payment가 터졌다:

```
[payment] 결제 승인(APPROVED) -> orderId=2, paymentId=2        ← order-events는 정상
ERROR o.s.kafka.listener.DefaultErrorHandler :
       Backoff FixedBackOff{interval=0, currentAttempts=1, maxAttempts=0} exhausted for product-events-0@1

org.springframework.kafka.listener.ListenerExecutionFailedException: Listener method could not be invoked
  Method [StockResultListener.onStockProcessed(StockProcessedEvent)]
Caused by: org.springframework.messaging.converter.MessageConversionException:
  Cannot convert from [com.commerce.payment.messaging.event.OrderCreatedEvent]
                  to [com.commerce.payment.messaging.event.StockProcessedEvent]
  for payload=OrderCreatedEvent[orderId=2, customerId=null, amount=0, items=[Item[productId=1, quantity=0]]],
      headers={... kafka_receivedTopic=product-events ...}
```

- **`order-events`(OrderCreated) 리스너는 정상** — default 타입과 일치하므로 결제 승인까지 잘 된다(대조군).
- **`product-events`(StockProcessed) 리스너만 깨진다**. 같은 서비스·같은 설정인데 한쪽만 죽는다.

### 원인 — 단일 default 타입은 "한 타입"만 섬긴다

1. `containerFactory`설정이 없으면 두 리스너 모두 **기본 팩토리**를 쓰고, 기본 팩토리의 역직렬화 타입은 `value.default.type` **하나(`OrderCreatedEvent`)** 뿐이다.
2. `product-events`로 온 `StockProcessed` JSON을 **`OrderCreatedEvent`로 강제 역직렬화**한다. 그런데 **이 단계에선 예외가 안 난다** — `StockProcessed`에만 있는 `result`/`reasonCode`/`productName`/`unitPrice`는 *조용히 버려지고*, `OrderCreatedEvent`에만 있는 `amount`·`quantity`는 JSON에 없어 **0으로 채워진** 잘못된 객체가 만들어진다. (로그의 `amount=0, quantity=0`)
3. **그다음 메서드 인자 바인딩 단계**에서 `OrderCreatedEvent → StockProcessedEvent` 변환 불가로 `MessageConversionException`이 터짐 
4. `Backoff ... maxAttempts=0 exhausted` → **재시도 없이 즉시 포기하고 offset을 커밋** = 그 메시지는 **그냥 버려짐**

### 해결 — 타입별 전용 컨테이너 팩토리 

`KafkaConsumerConfig`에서 타입마다 `JsonDeserializer`를 못박은 `ConcurrentKafkaListenerContainerFactory`를 만들고, 리스너가 `containerFactory`로 자기 타입 팩토리를 지정:

```java
private <T> ConcurrentKafkaListenerContainerFactory<String, T> typedFactory(Class<T> type) {
    JsonDeserializer<T> valueDeserializer = new JsonDeserializer<>(type, false); // 이 팩토리는 이 타입만
    valueDeserializer.addTrustedPackages("*");
    // ... DefaultKafkaConsumerFactory(props, StringDeserializer, valueDeserializer) ...
}

@Bean ConcurrentKafkaListenerContainerFactory<String, OrderCreatedEvent>  orderCreatedListenerFactory()  { return typedFactory(OrderCreatedEvent.class); }
@Bean ConcurrentKafkaListenerContainerFactory<String, StockProcessedEvent> stockProcessedListenerFactory() { return typedFactory(StockProcessedEvent.class); }
```

```java
@KafkaListener(topics = "order-events",   containerFactory = "orderCreatedListenerFactory")
@KafkaListener(topics = "product-events", containerFactory = "stockProcessedListenerFactory")
```

order-service도 같은 이유로 동일 구조(`PaymentProcessedEvent`/`StockProcessedEvent` 두 팩토리)를 가진다.

### 교훈

- **`spring.json.value.default.type`은 컨슈머당 정확히 한 타입만 섬긴다.** 한 서비스가 타입이 다른 토픽을 둘 이상 구독하면 단일 default로는 부족하다 → **타입별 `containerFactory`가 필수.**
- **타입 안 맞는 역직렬화의 진짜 위험은 "깨짐"이 아니라 "조용하게 지나감"이다.** 역직렬화는 성공해버리고(`amount=0`처럼 기본값으로 채운 *틀린 객체* 생성), 운 나쁘면 타입까지 우연히 맞아 **예외 없이 잘못된 비즈니스 동작**을 한다. 
- **`product-service`는 왜 `containerFactory`가 없나?** payment-events 한 토픽만 구독 = 타입이 하나뿐이라 default로 충분하다. **2개 이상 구독하는 서비스(order, payment)만** 타입별 팩토리가 필요하다. "필요해질 때 도입"의 좋은 예.
- **기본 에러 핸들러는 변환 실패를 재시도 없이 스킵할 수 있다**(`maxAttempts=0`). 만약 이게 `StockProcessed(FAILED)`였다면 **환불(보상)이 영원히 일어나지 않고 사슬이 침묵 속에 끊긴다.** choreography에서 한 컨슈머의 조용한 실패가 전체 Saga 정합성을 깨뜨릴 수 있다.

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