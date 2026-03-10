# Distributed Chat System — POC Spec

## 1. System Architecture

| Component | Technology | Port |
|---|---|---|
| Frontend | React | 3000 |
| Backend | Spring Boot (2 instances) | 8080, 8081 |
| Message Broker | Apache Kafka (KRaft mode) | 9092 |

---

## 2. Frontend (`chat-frontend`)

**Tech stack:** React, StompJS, SockJS

**Responsibilities:**
1. Provide a UI where the user enters a `userId` and selects which backend server to connect to (8080 or 8081).
2. Establish a WebSocket connection to the selected backend.
3. Send and receive messages in JSON format.

> Note: Manual server selection is intentional for POC testing purposes only. In production, a load balancer would sit in front and clients would connect to a single endpoint.

---

## 3. Backend (`chat-backend`)

**Tech stack:** Java 17, Spring Boot, WebSocket, Kafka

**Single-module structure (POC):**

```
chat-backend/
  src/main/java/com/example/chat_backend/
    config/       ← WebSocket config, Kafka config
    controller/   ← STOMP message handler (thin)
    service/      ← Business logic, MessagePublisher interface
    adapter/      ← KafkaMessagePublisher (implements MessagePublisher)
    listener/     ← KafkaMessageConsumer
    registry/     ← UserSessionRegistry
    dto/          ← ChatMessageDTO (immutable)
    constant/     ← KafkaTopic constants
```

**Component responsibilities:**

- `config/WebSocketConfig` — Configures STOMP over SockJS. Registers `/ws` as the WebSocket endpoint and `/app` as the application destination prefix. Enables user-destination messaging for `/user/{userId}/queue/messages`.
- `config/KafkaConfig` — Configures producer and consumer factories. **Each running instance must be given a unique `consumer.group-id`** (e.g., `chat-server-1` or `chat-server-2`) via `application.yaml` so that every instance receives every Kafka message (fan-out broadcast).
- `controller/ChatController` — Thin `@Controller`. Handles STOMP messages on `/app/chat.send`. Registers the connected user into `UserSessionRegistry`, then delegates to `ChatMessageService`.
- `service/MessagePublisher` — Technology-agnostic interface for publishing a chat message. Keeps the service layer decoupled from Kafka.
- `service/ChatMessageService` — `@Service`. Orchestrates: validate input → call `MessagePublisher` to publish to Kafka.
- `adapter/KafkaMessagePublisher` — Implements `MessagePublisher`. Encapsulates all Kafka producer logic and publishes `ChatMessageDTO` to the `chat.messages` topic.
- `listener/KafkaMessageConsumer` — `@KafkaListener` on `chat.messages`. On each consumed message, checks `UserSessionRegistry` for the `toUserId`. If found locally, delivers the message to that user's WebSocket session.
- `registry/UserSessionRegistry` — `@Component` that owns the `ConcurrentHashMap<String, WebSocketSession>`. Responsible for registering, deregistering, and looking up user sessions.
- `dto/ChatMessageDTO` — Immutable message DTO (`@Value`). Fields: `fromUserId`, `toUserId`, `content`, `timestamp`, `groupId`.
- `constant/KafkaTopic` — Constants for Kafka topic names (e.g., `CHAT_MESSAGES = "chat.messages"`).

> Critical: Unique consumer group IDs are what enable fan-out. If two instances share the same group ID, Kafka will load-balance between them and messages will be lost.

---

## 4. Infrastructure

**Tech stack:** Podman, Kafka 4.0 (KRaft — no Zookeeper required)

**Role:** Acts as the central message bus. All backend instances publish to and consume from the same Kafka topic `chat.messages`, enabling cross-instance message delivery.

---

## 5. Message Schema

```json
{
  "fromUserId": "alice",
  "toUserId": "bob",
  "content": "Hello!",
  "timestamp": "2026-02-27T10:00:00Z",
  "groupId": null
}
```

---

## 6. Scale-out Logic

- **Broadcast + local filter** — All backend instances subscribe to the same Kafka topic. Every instance checks its own local session map; only the instance holding the recipient's WebSocket connection performs the final delivery.
- Each backend instance runs in an isolated container (Podman handles network namespace and resource limits).

---

## 7. Local Development Setup

**Step 1 — Start Kafka (KRaft mode)**
Run a single-node Kafka instance via Podman. No Zookeeper needed.

**Step 2 — Start backend instances**
Build the single-module Spring Boot app, then run two instances with different Spring profiles:
- Instance 1: port `8080`, `consumer.group-id=chat-server-1`
- Instance 2: port `8081`, `consumer.group-id=chat-server-2`

**Step 3 — Start the frontend**
Run the React dev server on port `3000`.

---

## 8. Future Extensions (Out of Scope for POC)

| Feature | Approach |
|---|---|
| Group chat | Store group membership in Redis; fan-out delivery to each member |
| Message persistence | Store history in PostgreSQL |
| Precise routing | Store `userId → serverId` mapping in Redis to reduce unnecessary fan-out broadcast |

---

# PROJECT ARCHITECTURE PATTERNS

Clean Architecture approach with Spring Boot. **Do NOT follow DDD patterns.**

---

## 0. Fundamentals (Java Code Layer)

- Support Backward Compatible design
- Follow Immutable Functional Design (Avoid Call by Reference)
- Avoid common code smells: Feature Envy, Shotgun Surgery, God Object
- No Magic Numbers — use `static final` Constants or Enum
- Prefer Composition over Inheritance
- Avoid NPE with `Optional`
- Avoid Static Class and Inner Class
- Use Lombok by default

---

## 0.1 Logging Standards (Mandatory)

All logging must support **SLI measurement** and **Kafka Stream + ELK/PLG** architecture.

### Log Format (Unified)

```
[Module][Operation] Status. key1={}, key2={}
```

- **Module**: Service/Adapter name — e.g., `Chat-Service`, `Kafka-Publisher`
- **Operation**: Operation type — e.g., `Query`, `Update`, `Publish`, `Consume`
- **Status**: `Start.` / `Success.` / `Failed!` / `Retry triggered.` / `Fallback activated.`
- **key={}**: Use `=` for key-value pairs (supports log parsing)

### Log Level Guidelines

| Level | Usage | Alert |
|-------|-------|-------|
| **DEBUG** | Detailed diagnostics. Disabled in production. | None |
| **INFO** | Critical path, business state changes, SLI tracking (throughput/latency). | None |
| **WARN** | Predictive anomalies: external jitter, retry triggered, 4xx errors. | Non-immediate |
| **ERROR** | Fatal: DB failure, 5xx, unexpected RuntimeException, DLQ, data loss risk. | Immediate (P0) |

### Mandatory Context

- **traceId**: Always include `MDC.get("traceId")` (injected via OpenTelemetry or custom utility)
- **Business Key**: Include domain identifier (userId, orderNo, goodsCode, fileId)
- **duration**: Include `duration={}ms` for all operations (latency SLI)
- **Input Params**: Include on ERROR

### Examples

```java
// GOOD: Structured with full context
log.info("[Chat-Service][Publish] Success. fromUserId={}, toUserId={}, duration={}ms",
    from, to, System.currentTimeMillis() - startTime);

// GOOD: WARN for retriable errors
log.warn("[Kafka-Publisher][Publish] Network error, retry triggered. toUserId={}, duration={}ms, cause={}",
    toUserId, duration, e.getMessage());

// GOOD: ERROR for critical failures
log.error("[Kafka-Publisher][Fallback] Circuit open! toUserId={}, action=QueueForRetry", toUserId, e);

// BAD: Missing context
log.error("Update failed");

// BAD: Wrong level for retriable error
log.error("Network timeout, will retry", e);
```

### Performance

- Use async appenders; avoid blocking threads
- Use `log.isDebugEnabled()` before expensive string ops
- Avoid logging in high-frequency loops
- Use `{}` placeholders, never string concatenation

---

## 1. Controller Pattern (Infrastructure Layer)

**Responsibility:** Thin — only input/output transformation, validation, auth. Delegate all logic to Service.

**Input/Output:**
- Response: `ResponseEntity<ApiResponse<T>>`
- Use dedicated Request/Response DTOs. **Never expose Domain Entities directly.**
- API pattern: `/api/${version}/${category}/${resource}/${subType}:${action}` (Google AIP)

**Annotations:**
- `@RestController`, `@RequiredArgsConstructor`
- `@PostMapping`, `@GetMapping`, `@PutMapping`, etc.
- `@PathVariable`, `@RequestBody`, `@Valid` / `@Validated`

---

## 2. Service Pattern (Application / Use Case Layer)

**Responsibility:** Core business logic. Coordinates domain and external resources. **Only layer with `@Transactional`.**

**Dependencies:** Depend on interfaces only (DIP). Use `@RequiredArgsConstructor` for injection.

**Annotations:**
- `@Service`, `@RequiredArgsConstructor`, `@Slf4j`
- Write: `@Transactional(value = "tx0", propagation = Propagation.REQUIRED, rollbackFor = Exception.class)`
- Read: `@Transactional(value = "tx0", readOnly = true, propagation = Propagation.REQUIRED, rollbackFor = Exception.class)`

**Rules:**
- **MUST** use `@Transactional` at method level in Service only
- **FORBIDDEN**: `@Transactional` in DAO/Repository layer
- Throw specific business exceptions; let Controller Advice map to HTTP codes

**Transaction Patterns:**

```java
// Read
@Transactional(value = "tx0", readOnly = true, rollbackFor = Exception.class)
public GoodsDTO getGoods(String code) {
    log.info("[Goods-Service][Query] Start. code={}, traceId={}", code, MDC.get("traceId"));
    long start = System.currentTimeMillis();
    try {
        GoodsDTO result = goodsRepository.findByCode(code)
            .orElseThrow(() -> new GoodsNotFoundException(code));
        log.info("[Goods-Service][Query] Success. code={}, duration={}ms", code, System.currentTimeMillis() - start);
        return result;
    } catch (GoodsNotFoundException e) {
        log.warn("[Goods-Service][Query] Not found. code={}", code);
        throw e;
    } catch (Exception e) {
        log.error("[Goods-Service][Query] Failed! code={}, duration={}ms, cause={}", code, System.currentTimeMillis() - start, e.getMessage(), e);
        throw e;
    }
}

// Write
@Transactional(value = "tx0", rollbackFor = Exception.class)
public void updateGoods(GoodsDTO dto) { ... }

// Mixed (read + write)
@Transactional(value = "tx0", rollbackFor = Exception.class)
public void processOrder(String orderNo) { ... }
```

---

## 3. Repository Pattern (Persistence Adapter)

**Responsibility:**
- Interface defined in Domain layer (only methods required by Service)
- Implementation in Infrastructure layer
- Maps between Domain Entity and Persistence Entity. **Never let Persistence Entity leak into Service.**
- DAO encapsulates complex SQL. **No business logic in DAO.**

**Rules:**
- Use `JdbcTemplate` for native SQL (JOIN, UNION, dynamic conditions)
- **FORBIDDEN**: Return `List<Map<String, Object>>` — use `RowMapper` for strongly-typed DTOs
- SQL constants: `private static final String`
- Dynamic conditions: use parameterized queries to avoid SQL Injection
- **FORBIDDEN**: `@Transactional` in DAO/Repository

**Annotations:**
- `@Repository`
- Extend `CrudRepository` by default; support `JpaSpecificationExecutor` for criteria queries
- `@Autowired` + `@Qualifier` for specific JdbcTemplate injection

**RowMapper Pattern:**

```java
private static final RowMapper<LimitBuyDTO> LIMIT_BUY_MAPPER = (rs, rowNum) -> {
    LimitBuyDTO dto = new LimitBuyDTO();
    dto.setLbcode(rs.getString("LBCODE"));
    dto.setGoodsCode(rs.getString("GOODS_CODE"));
    dto.setSpecialPrice(rs.getBigDecimal("SPECIAL_PRICE"));
    return dto;
};

public List<LimitBuyDTO> findLimitBuyList(LimitBuyVO vo) {
    return jdbcTemplate.query(sqlBuilder.buildQuery(vo), LIMIT_BUY_MAPPER, sqlBuilder.buildArgs(vo));
}
```

**Multi-datasource failover:** Encapsulate in `DualDataSourceExecutor` — try primary, fallback to secondary, log WARN on failover, ERROR on both failure.

**Naming:**
- DAO class: `${DomainName}Dao` (e.g., `LimitBuyDao`)
- Methods: `find${Entity}List`, `update${Entity}`, `delete${Entity}`
- RowMapper: `${ENTITY}_MAPPER`

---

## 4. Domain Model (DTO / Entity)

**Design Principles:**
- SRP: Entities have behavior for state change; DTOs carry data only
- No I/O operations in Domain or DTO
- Immutability: use Lombok `@Value` for immutable DTOs

**Naming:**
- Entity: `${DomainName}` (no "Entity" suffix) — e.g., `LimitBuy`
- Request DTO: `${DomainName}Request`
- Response DTO: `${DomainName}DTO`
- VO (internal only): `${DomainName}VO`
- Enums for lifecycle status: in `constant` package of common module

**Entity Annotations:**
- `@Entity`, `@Table(name = "xxx")`, `@Id`, `@GeneratedValue(strategy = GenerationType.IDENTITY)`
- Lombok: `@Data`, `@ToString`, `@EqualsAndHashCode`

**DTO Annotations:**
- `@Data` (Lombok), `@Schema` (OpenAPI), `@JsonProperty` (Jackson)
- Request DTO: `@Valid`, `@NotNull`, `@Size`

**Optional patterns:**
- Optimistic locking: `version` field (initial = 1), increment on each state change
- State change via entity method, not direct field set

---

## 5. External Integration Adapter Pattern (Infrastructure Layer)

### 5.1 Core Principles

- **Gateway Interface** in Application/Service layer (technology-agnostic)
- **Adapter Implementation** in Infrastructure layer (encapsulates protocol details, mapping, error handling)
- Service depends on interface, not implementation (DIP / OCP)

**Error Handling Hierarchy (all adapters):**
```
1. Business Exception   — domain-specific (e.g., PaymentDeclinedException) — DON'T RETRY
2. I/O Exception        — network, connection errors — RETRY
3. Timeout Exception    — request timeout — RETRY
4. Protocol Exception   — HTTP 5xx, Kafka serialization errors — RETRY
5. Unexpected Exception — unknown errors — FALLBACK
```

**Resilience Patterns (mandatory for all external calls):**
- **Circuit Breaker** — prevent cascading failures (Resilience4j)
- **Retry** — exponential backoff for transient failures
- **Timeout** — explicit timeout on every call
- **Fallback** — graceful degradation
- **Bulkhead** — isolate thread pools per external system

### 5.2 REST API Integration

**Stack:** `RestTemplate` / `WebClient`, Resilience4j, Jackson

**Structure:**
```java
public interface PaymentGateway {
    PaymentResult processPayment(PaymentRequest request);
}

@Component @Slf4j
public class ThirdPartyPaymentAdapter implements PaymentGateway {
    @Override
    @CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
    @Retry(name = "paymentService")
    @TimeLimiter(name = "paymentService")
    public PaymentResult processPayment(PaymentRequest request) {
        log.info("[Payment-Adapter][API] Start. orderNo={}, traceId={}", request.getOrderNo(), MDC.get("traceId"));
        long start = System.currentTimeMillis();
        try {
            // call external API, map response
            log.info("[Payment-Adapter][API] Success. orderNo={}, duration={}ms", request.getOrderNo(), System.currentTimeMillis() - start);
            return result;
        } catch (PaymentBusinessException e) {
            log.error("[Payment-Adapter][API] Business error! orderNo={}, errorCode={}", request.getOrderNo(), e.getErrorCode());
            throw e; // don't retry
        } catch (ResourceAccessException e) {
            log.warn("[Payment-Adapter][API] Network error, retry triggered. orderNo={}, cause={}", request.getOrderNo(), e.getMessage());
            throw new PaymentIOException("Network error", e);
        } catch (HttpServerErrorException e) {
            log.warn("[Payment-Adapter][API] Server error (5xx), retry triggered. orderNo={}, status={}", request.getOrderNo(), e.getStatusCode());
            throw new PaymentServerException("Service unavailable", e);
        } catch (Exception e) {
            log.error("[Payment-Adapter][API] Unexpected error! orderNo={}, cause={}", request.getOrderNo(), e.getMessage(), e);
            throw new PaymentSystemException("System error", e);
        }
    }

    private PaymentResult paymentFallback(PaymentRequest request, Exception e) {
        log.error("[Payment-Adapter][Fallback] Circuit open! orderNo={}, action=QueueForRetry", request.getOrderNo());
        return PaymentResult.builder().status(PaymentStatus.PENDING).requiresManualReview(true).build();
    }
}
```

### 5.3 Message Queue (Kafka) Integration

**Stack:** Spring Kafka, Jackson

**Gateway + Producer structure:**
```java
public interface MessagePublisher {
    void publish(ChatMessageDTO message);
}

@Component @Slf4j
public class KafkaMessagePublisher implements MessagePublisher {
    private final KafkaTemplate<String, ChatMessageDTO> kafkaTemplate;

    @Override
    @CircuitBreaker(name = "kafkaPublisher", fallbackMethod = "publishFallback")
    @Retry(name = "kafkaPublisher")
    public void publish(ChatMessageDTO message) {
        log.info("[Kafka-Publisher][Publish] Start. toUserId={}, topic={}, traceId={}",
            message.getToUserId(), KafkaTopic.CHAT_MESSAGES, MDC.get("traceId"));
        long start = System.currentTimeMillis();
        try {
            kafkaTemplate.send(KafkaTopic.CHAT_MESSAGES, message.getToUserId(), message)
                .addCallback(
                    result -> log.info("[Kafka-Publisher][Publish] Success. toUserId={}, partition={}, offset={}, duration={}ms",
                        message.getToUserId(), result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(), System.currentTimeMillis() - start),
                    ex -> log.error("[Kafka-Publisher][Publish] Async failed! toUserId={}, cause={}", message.getToUserId(), ex.getMessage(), ex)
                );
        } catch (SerializationException e) {
            log.error("[Kafka-Publisher][Publish] Serialization failed! toUserId={}, cause={}", message.getToUserId(), e.getMessage(), e);
            throw new MessageSerializationException("Serialize failed", e);
        } catch (Exception e) {
            log.warn("[Kafka-Publisher][Publish] Publish failed, will retry. toUserId={}, duration={}ms, cause={}",
                message.getToUserId(), System.currentTimeMillis() - start, e.getMessage());
            throw new MessagePublishException("Publish failed", e);
        }
    }

    private void publishFallback(ChatMessageDTO message, Exception e) {
        log.error("[Kafka-Publisher][Fallback] Circuit open, saving to outbox. toUserId={}, triggerCause={}",
            message.getToUserId(), e.getClass().getSimpleName());
        // Save to outbox table for retry
    }
}
```

**Consumer structure:**
```java
@Component @Slf4j
public class KafkaMessageConsumer {
    @KafkaListener(topics = "${kafka.topic}", groupId = "${kafka.consumer.group-id}")
    @RetryableTopic(attempts = "3", backoff = @Backoff(delay = 1000, multiplier = 2.0),
        exclude = {MessageValidationException.class}, dltTopicSuffix = "-dlt")
    public void consume(@Payload ChatMessageDTO message,
                        @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset) {
        MDC.put("traceId", message.getTraceId());
        log.info("[Kafka-Consumer][Consume] Received. toUserId={}, partition={}, offset={}", message.getToUserId(), partition, offset);
        long start = System.currentTimeMillis();
        try {
            // deliver to local WebSocket session if user connected here
            log.info("[Kafka-Consumer][Consume] Success. toUserId={}, duration={}ms", message.getToUserId(), System.currentTimeMillis() - start);
        } catch (MessageValidationException e) {
            log.error("[Kafka-Consumer][Consume] Validation failed, sending to DLT. toUserId={}, cause={}", message.getToUserId(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[Kafka-Consumer][Consume] Failed! toUserId={}, duration={}ms, cause={}", message.getToUserId(), System.currentTimeMillis() - start, e.getMessage(), e);
            throw new MessageProcessingException("Processing failed", e);
        } finally {
            MDC.clear();
        }
    }

    @DltHandler
    public void handleDlt(@Payload ChatMessageDTO message, @Header(KafkaHeaders.EXCEPTION_MESSAGE) String error) {
        log.error("[Kafka-DLT][Consume] Sent to DLQ! toUserId={}, error={}, action=ManualReview", message.getToUserId(), error);
    }
}
```

### 5.4 Cache (Redis) Integration

**Stack:** Spring Data Redis, Lettuce, Redisson

**Gateway Interface:**
```java
public interface CacheGateway {
    <T> Optional<T> get(String key, Class<T> type);
    <T> void put(String key, T value, Duration ttl);
    void delete(String key);
}
```

**Error Handling Strategy:**
```
Business Exception (invalid key format)       → throw, don't cache
Serialization Exception                        → treat as cache miss
Connection Exception (Redis unavailable)       → fallback to DB
Timeout Exception                              → fallback to DB
Unexpected Exception                           → degrade gracefully (return empty)
```

**Key rules:**
- Cache write failures must NOT break business flow (swallow or log WARN)
- Cache delete failures are acceptable (idempotent)
- Use Cache-Aside pattern: read cache → miss → query DB → async write to cache
- Invalidate cache on write operations (write-through)

### 5.5 File I/O Integration

**Stack:** Spring Resource, AWS S3 / Azure Blob / Local FS, Apache Commons IO

**Gateway Interface:**
```java
public interface FileStorageGateway {
    String uploadFile(String fileName, InputStream inputStream, long fileSize);
    InputStream downloadFile(String fileId);
    void deleteFile(String fileId);
}
```

**Error Handling Strategy:**
```
Business Exception (invalid format/size)       → throw, don't retry
I/O Exception (read/write errors)              → throw
Connection Exception (S3 unavailable)          → RETRY → fallback to temp storage
Timeout Exception                              → RETRY
Fallback: save to local temp + queue for retry
Critical: both S3 and local fail = P0 alert
```

### 5.6 Resilience4j Configuration

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        registerHealthIndicator: true
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        permittedNumberOfCallsInHalfOpenState: 3
        automaticTransitionFromOpenToHalfOpenEnabled: true
        waitDurationInOpenState: 30s
        failureRateThreshold: 50
    instances:
      paymentService:
        baseConfig: default
        failureRateThreshold: 60
      kafkaPublisher:
        baseConfig: default
        failureRateThreshold: 70
      redisCache:
        baseConfig: default
        failureRateThreshold: 80
      s3Storage:
        baseConfig: default
        failureRateThreshold: 50

  retry:
    configs:
      default:
        maxAttempts: 3
        waitDuration: 1s
        exponentialBackoffMultiplier: 2
        enableExponentialBackoff: true
    instances:
      kafkaPublisher:
        baseConfig: default
        maxAttempts: 5
      paymentService:
        baseConfig: default
        ignoreExceptions:
          - com.example.exception.PaymentBusinessException

  timelimiter:
    configs:
      default:
        timeoutDuration: 10s
    instances:
      paymentService:
        timeoutDuration: 15s
      kafkaPublisher:
        timeoutDuration: 5s
      redisCache:
        timeoutDuration: 2s
      s3Storage:
        timeoutDuration: 30s

  bulkhead:
    instances:
      paymentService:
        maxConcurrentCalls: 10
        maxWaitDuration: 5s
      kafkaPublisher:
        maxConcurrentCalls: 20
      s3Storage:
        maxConcurrentCalls: 5
```

### 5.7 Monitoring Key Metrics

- Circuit Breaker State (Open/Closed/Half-Open)
- Retry Count per operation
- Timeout Rate
- Fallback Trigger Rate
- Error Rate by Type (Business / IO / Timeout / Protocol)
- Response Time (P50, P95, P99)

**Actuator endpoints:**
```
GET /actuator/health
GET /actuator/metrics/resilience4j.circuitbreaker.calls
GET /actuator/metrics/resilience4j.retry.calls
```

**Exception hierarchy:**
```
ExternalIntegrationException (base)
├── BusinessException  (4xx, validation)  — DON'T RETRY
├── IOException        (network)          — RETRY
├── TimeoutException   (request timeout)  — RETRY
├── ProtocolException  (HTTP 5xx, Kafka)  — RETRY
└── SystemException    (unexpected)       — FALLBACK
```

---

## 6. Test

- Framework: JUnit + Spring Test + Mockito
- Tests must be idempotent

**Naming:** `test${MethodName}_${Scenario}` (camelCase)
- e.g., `testCreateOrder_success`, `testCreateOrder_noSuchProductFail`

**Unit Test:**
- Single unit with Mockito
- Do NOT test DAO/Repository layer
- Cover basic happy path and failure cases

**API Integration Test:**
- Use Spring MVC Test
- Test data must be idempotent

**Structure:** Follow Gherkin format (Given / When / Then) to arrange test methods.

---

## 7. Project Structure

```
Root
  Module1
    module1-api      ← config, controller, interceptor, listener, scheduler, Application
    module1-service  ← service
    module1-dao      ← repository, dao, entity, mapper
    module1-common   ← constant, dto, util
  Module2
    ...
```

Each sub-module mirrors `src/main` with `src/test` using the same package structure.
