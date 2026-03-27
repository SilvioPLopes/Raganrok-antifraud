# ragnarok-antifraude

An independent anti-fraud microservice for Ragnarok Online, built with **Hexagonal Architecture (Ports & Adapters)** using Java 21 and Virtual Threads. It receives game events via REST and returns a decision (`APPROVED`, `BLOCKED`, or `CHALLENGE`) within a guaranteed **p99 of 80ms** — the game never freezes waiting for the anti-fraud system.

---

## Table of Contents

- [The Problem](#the-problem)
- [The Solution](#the-solution)
- [Architecture](#architecture)
- [The 9 Rules](#the-9-rules)
- [Tech Stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [API Endpoints](#api-endpoints)
- [Integration with ragnarok-core](#integration-with-ragnarok-core)
- [Configuration](#configuration)
- [Database](#database)
- [Stress Test](#stress-test)
- [Docker](#docker)
- [Tests](#tests)
- [Security](#security)
- [Architectural Decisions](#architectural-decisions)
- [Project Structure](#project-structure)
- [Troubleshooting](#troubleshooting)
- [Known Issues](#known-issues)
- [Roadmap](#roadmap)

---

## The Problem

An anti-fraud engine that runs 9 rules sequentially before each player action can easily exceed 100ms:

```
Rule 1 (3ms) + Rule 2 (5ms) + ... + Rule 9 (3ms) = ~35ms sequential
+ HTTP overhead + serialization + database access = >100ms
```

In an MMO like Ragnarok Online, 100ms of added latency on combat or item trading makes the game unplayable. Attacking a monster, trading an item, opening a vending shop — everything freezes while waiting for the anti-fraud system to respond.

The central challenge: **how to run 9 complex checks without the player noticing they exist?**

---

## The Solution

Four architectural decisions eliminate the latency risk:

**1. Rules run in parallel with Virtual Threads (Java 21).**
All 9 rules are fired simultaneously. The total time equals the *slowest* rule (≈ 5ms), not the sum of all. Virtual Threads have near-zero overhead for I/O-bound tasks — each rule runs in its own virtual thread without pool cost.

**2. Hard 50ms timeout.**
If any rule doesn't return within 50ms, it's ignored. The engine returns `APPROVED` for rules that completed. The game never waits more than 50ms for the anti-fraud system.

**3. Redis as L1 cache for hot data.**
Anti-dupe locks, farm sessions, click timing, login country, market prices — all live in Redis with sub-millisecond reads. PostgreSQL is only accessed for cold data (audit log, instance cooldowns, price seed).

**4. 100% async audit log.**
Persisting the result to PostgreSQL happens *after* the response has already been sent to ragnarok-core. The database is never on the critical path.

```
                    ┌─ Rule 1 (Redis SET NX)        ≈ 3ms ─┐
                    ├─ Rule 2 (Redis GET price)      ≈ 5ms ─┤
   FraudEvent ──────├─ Rule 3 (Redis HGET session)   ≈ 2ms ─├──→ FraudDecision
   from core        ├─ Rule 4 (Redis ZADD window)    ≈ 2ms ─┤    in < 50ms
                    ├─ Rule 5 (payload check)         ≈ 1ms ─┤
                    ├─ Rule 6 (Redis GET doc)         ≈ 5ms ─┤
                    ├─ Rule 7 (PostgreSQL indexed)    ≈ 4ms ─┤
                    ├─ Rule 8 (Redis HGET login)      ≈ 2ms ─┤
                    └─ Rule 9 (payload math)          ≈ 3ms ─┘
                                                             │
                                     Audit log ─────────────┘ (async, off critical path)
```

---

## Architecture

The project follows **Hexagonal Architecture** with 3 layers and a strict dependency rule:

```
domain → (nothing)
application → domain
infrastructure → application + domain
```

The domain is pure Java — zero Spring, Redis, JPA, or framework imports. This ensures business logic is testable without any infrastructure.

```
┌─────────────────────────────────────────────────────────────────────┐
│  DOMAIN (pure Java)                                                 │
│                                                                     │
│  model/          FraudEvent (immutable record)                      │
│                  FraudDecision (immutable record)                   │
│                  Verdict, RequiredAction, RiskLevel (enums)         │
│                                                                     │
│  rule/           FraudRule (interface) + RuleResult (record)        │
│                                                                     │
│  port/in/        FraudAnalysisUseCase                               │
│  port/out/       TransactionRepository                              │
│                  PlayerActivityRepository                           │
│                  AuditRepository                                    │
├─────────────────────────────────────────────────────────────────────┤
│  APPLICATION (orchestration)                                        │
│                                                                     │
│  service/        FraudAnalysisService (parallel engine)             │
│                  AuditLogService (async persistence)                │
│                                                                     │
│  rule/           AntiDupeRule, DisproportionateTransferRule,        │
│                  BotFarmTimeRule, BotClickSpeedRule,                │
│                  RegistrationValidationRule, CashSecurityRule,      │
│                  InstanceCooldownRule, ImpossibleTravelRule,        │
│                  MarketMonopolyRule                                 │
├─────────────────────────────────────────────────────────────────────┤
│  INFRASTRUCTURE (concrete implementations)                          │
│                                                                     │
│  adapter/in/     FraudController (POST /api/fraud/analyze)          │
│    rest/         PlayerStateController (state sync)                 │
│                  AdminDashboardController (GMs)                     │
│                  SimulationController (scenario testing)            │
│                  DTOs + Mapper                                      │
│                                                                     │
│  adapter/out/    RedisTransactionAdapter                            │
│    redis/        RedisPlayerActivityAdapter                         │
│                                                                     │
│  adapter/out/    JpaAuditAdapter                                    │
│    persistence/  FraudAuditEntity + FraudAuditJpaRepository         │
│                                                                     │
│  config/         AsyncConfig (thread pools)                         │
│                  ApiKeyFilter (inter-service auth)                  │
│                                                                     │
│  scheduler/      MarketPriceRefresher (PostgreSQL → Redis every 5m) │
│  simulation/     SimulationService (10 fraud scenarios)             │
└─────────────────────────────────────────────────────────────────────┘
```

---

## The 9 Rules

Each rule implements the `FraudRule` interface with internal try/catch — if it fails, it returns `APPROVED` (fail-open). All rules are stateless and thread-safe.

### Rule 1 — Anti-Dupe (`ANTI_DUPE`)

Prevents item cloning. Uses an atomic Redis `SET NX` on the item UUID with a 500ms TTL. If the same UUID appears twice in that window, the second transaction is blocked.

- **EventType:** `ITEM_TRADE`
- **Action:** `CANCEL_ACTION`
- **Storage:** Redis
- **Latency:** < 3ms

### Rule 2 — Disproportionate Transfer (`DISPROPORTIONATE_TRANSFER`)

Compares the zeny value of a trade against the item's median market price (Redis cache, updated every 5 minutes by `MarketPriceRefresher`).

- **EventType:** `ITEM_TRADE`
- **Thresholds:** ratio > 100x → `FLAG_FOR_REVIEW` | > 1000x → `CANCEL_ACTION` | > 10000x → `CANCEL_ACTION` + `CRITICAL`
- **Storage:** Redis (cached prices)
- **Latency:** < 5ms

### Rule 3 — Bot Farm Time (`BOT_FARM_TIME`)

Detects continuous farming on the same map. ragnarok-core sends periodic heartbeats; the anti-fraud calculates session duration.

- **EventType:** `FARM_HEARTBEAT`
- **Thresholds:** > 6h → `SHOW_CAPTCHA` | > 12h → `FLAG_FOR_REVIEW` | > 24h → `DROP_SESSION`
- **Storage:** Redis (session start timestamp)
- **Latency:** < 2ms

### Rule 4 — Bot Click Speed (`BOT_CLICK_SPEED`)

Analyzes actions per second (APS) and the standard deviation of click intervals. Humans have irregular timing; bots have robotically regular timing (stddev < 5ms). Uses a real sliding window (Redis sorted set, last 10s).

- **EventType:** `CLICK_ACTION`
- **Thresholds:** APS > 15 → `SHOW_CAPTCHA` | APS > 25 or stddev < 5ms → `FLAG_FOR_REVIEW`
- **Storage:** Redis (sorted set with 10s sliding window)
- **Latency:** < 2ms

### Rule 5 — Registration Validation (`REGISTRATION_VALIDATION`)

Verifies that the email was confirmed and the player meets the age requirement before allowing login.

- **EventType:** `SESSION_LOGIN`, `ACCOUNT_REGISTRATION`
- **Action:** `CANCEL_ACTION`
- **Storage:** None (data in payload)
- **Latency:** < 1ms

### Rule 6 — Cash Security (`CASH_SECURITY`)

Blocks cash purchases (ROPs) if the CPF/CNPJ provided in billing does not match the registered account holder's document.

- **EventType:** `CASH_PURCHASE`
- **Action:** `CANCEL_ACTION`
- **Storage:** Redis (player document)
- **Latency:** < 5ms

### Rule 7 — Instance Cooldown (`INSTANCE_COOLDOWN`)

Prevents re-entry into special maps (MVP rooms, high-level dungeons) before the cooldown expires. Cooldowns range from days to weeks.

- **EventType:** `MAP_INSTANCE_ENTRY`
- **Action:** `CANCEL_ACTION`
- **Storage:** PostgreSQL (durable, survives restarts) + Redis (cache)
- **Latency:** < 4ms

> **Known issue:** `recordInstanceEntry()` currently only writes to Redis. PostgreSQL persistence (source of truth) is not yet implemented — cooldowns are lost on Redis restart.

### Rule 8 — Impossible Travel (`IMPOSSIBLE_TRAVEL`)

If the login country changes in less than 2 hours, the account was likely compromised. The session is immediately dropped.

- **EventType:** `SESSION_LOGIN`
- **Action:** `DROP_SESSION`
- **Storage:** Redis (last country + timestamp)
- **Latency:** < 2ms

### Rule 9 — Market Monopoly (`MARKET_MONOPOLY`)

Detects attempts to buy a disproportionate share of an item's stock to manipulate the game economy.

- **EventType:** `MARKET_PURCHASE`
- **Thresholds:** > 80% of stock → `ALERT_ONLY` | > 95% → `FLAG_FOR_REVIEW`
- **Storage:** None (math on payload)
- **Latency:** < 3ms

---

## Tech Stack

| Component | Technology | Reason |
|-----------|-----------|--------|
| Runtime | Java 21 + Virtual Threads | Massive parallelism without thread pool overhead |
| Framework | Spring Boot 3.2.5 | Mature ecosystem, Java 21 compatible |
| Cold storage | PostgreSQL 16 | Audit log, cooldowns, price seed |
| Hot cache | Redis 7 | Sub-millisecond reads for all stateful rules |
| L1 cache | Caffeine | In-process cache for constantly read data |
| Migrations | Flyway | Automatic schema versioning |
| Build | Maven Wrapper | No installed Maven required |
| Container | Docker multi-stage | JRE-only, non-root, ZGC, ~200MB |
| GC | ZGC | < 1ms pauses — ideal for latency-sensitive workloads |
| API Docs | springdoc-openapi | Automatic Swagger UI |
| Metrics | Micrometer + Prometheus | p99 latency, verdict counts, rule counters |
| Dashboards | Grafana | Pre-provisioned dashboards on port 3000 |
| Tests | JUnit 5 + Testcontainers | Integration with real PostgreSQL and Redis |
| Coverage | JaCoCo | Coverage report on build |

---

## Prerequisites

**To run with Docker (recommended):** only **Docker** and **Docker Compose**.

**To run locally without Docker:** Java 21+ (JDK), PostgreSQL 16+ on port 5432, Redis 7+ on port 6379.

---

## Quick Start

### With Docker (recommended)

```bash
# Start PostgreSQL + Redis + antifraude
docker compose up -d postgres redis antifraude

# Check health
docker compose ps

# Follow logs
docker compose logs -f antifraude

# Test the health endpoint
curl http://localhost:8081/api/fraud/health
```

> **Important:** To stop without losing data, always use `docker compose stop`. Never use `docker compose down -v` unless you want to reset everything — it destroys all volumes including the database.

### Local (dev mode — connects to Docker PostgreSQL/Redis)

```bash
# 1. Start only the infrastructure
docker compose up -d postgres redis

# 2. Build
./mvnw clean install -DskipTests

# 3. Run (port 8081)
# Note: Docker maps PostgreSQL to external port 5433
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/ragnarok_antifraude_db \
DB_PASS=postgre ANTIFRAUDE_API_KEY=dev-key-123 ./mvnw spring-boot:run

# 4. Verify
curl http://localhost:8081/api/fraud/health
```

### Swagger UI

With the service running: [http://localhost:8081/swagger-ui.html](http://localhost:8081/swagger-ui.html) (no API key required)

---

## API Endpoints

### Fraud Analysis

The main endpoint. ragnarok-core calls this **before** committing any sensitive action.

```
POST /api/fraud/analyze
Content-Type: application/json
X-API-Key: your-key
```

**Request:**
```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "ITEM_TRADE",
  "playerId": 12345,
  "ipAddress": "200.100.50.25",
  "countryCode": "BR",
  "occurredAt": "2026-03-27T14:30:00Z",
  "payload": {
    "itemId": 4324,
    "itemUuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "zenysValue": 25000000
  }
}
```

**Response (approved):**
```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "playerId": 12345,
  "verdict": "APPROVED",
  "requiredAction": "NONE",
  "riskLevel": "LOW",
  "triggeredRules": [],
  "reason": "All rules passed",
  "processingTimeMs": 7
}
```

**Response (blocked):**
```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "playerId": 12345,
  "verdict": "BLOCKED",
  "requiredAction": "CANCEL_ACTION",
  "riskLevel": "CRITICAL",
  "triggeredRules": ["ANTI_DUPE"],
  "reason": "Duplicate item trade detected: a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "processingTimeMs": 4
}
```

**Supported Event Types:**

| EventType | Rules triggered | Required payload fields |
|-----------|----------------|------------------------|
| `ITEM_TRADE` | 1, 2 | `itemId`, `itemUuid`, `zenysValue` |
| `CLICK_ACTION` | 4 | `actionsPerSecond`, `networkLatencyMs` |
| `FARM_HEARTBEAT` | 3 | `mapId` |
| `SESSION_LOGIN` | 5, 8 | `emailVerified`, `ageVerified` |
| `ACCOUNT_REGISTRATION` | 5 | `emailVerified`, `ageVerified` |
| `CASH_PURCHASE` | 6 | `billingName`, `billingDocument` |
| `MAP_INSTANCE_ENTRY` | 7 | `mapId` |
| `MARKET_PURCHASE` | 9 | `itemId`, `quantityRequested`, `totalInStock` |

### State Sync (fire-and-forget)

ragnarok-core calls these endpoints asynchronously to keep the anti-fraud state up to date. If they fail, the game is not affected.

```
POST /api/fraud/state/player/{id}/map-leave?mapId=prt_maze03
POST /api/fraud/state/player/{id}/login?countryCode=BR
POST /api/fraud/state/player/{id}/instance-enter
     Body: { "mapId": "mvp_room01", "cooldownSeconds": 604800 }
POST /api/fraud/state/player/{id}/registration
     Body: { "emailVerified": true, "ageVerified": true, "document": "123.456.789-00" }
```

### Simulation

Run predefined fraud scenarios for manual testing. No API key required in dev mode.

```
POST /api/simulate/{scenario}?count=50
```

Available scenarios: `normal-traffic`, `item-dupe`, `disproportionate-transfer`, `bot-farm`, `bot-attack`, `unregistered-account`, `cash-fraud`, `instance-spam`, `impossible-travel`, `market-monopoly`

### Admin / Dashboard

Endpoints for game masters investigating suspicious players.

```
GET /api/fraud/admin/player/{id}/history?limit=50
GET /api/fraud/admin/player/{id}/blocks?hours=24
```

### Health

```
GET /api/fraud/health
```

Public endpoint — no API key required.

---

## Integration with ragnarok-core

The `integration-core/` directory contains files that must be copied **into** ragnarok-core:

| File | Destination in ragnarok-core | Purpose |
|------|------------------------------|---------|
| `FraudClient.java` | `com/ragnarok/infrastructure/antifraude/` | HTTP client with Circuit Breaker + fallback |
| `UsageExamples.java` | same package | Usage examples in BattleService, ItemService, MapService, LoginService |
| `application-antifraude.yml` | merge into core's `application.yml` | Resilience4j configuration (CB + timeout) |

> **Current status:** The `FraudClient.java` currently deployed in ragnarok-core is a **stub** that always returns `FALLBACK_APPROVED` without making any HTTP call. The real implementation (with RestTemplate, CircuitBreaker, and actual HTTP calls) is ready in `integration-core/FraudClient.java` and needs to replace the stub.

**Dependencies to add to ragnarok-core's pom.xml:**

```xml
<!-- WebClient for async fire-and-forget state sync -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>

<!-- Resilience4j for circuit breaker -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.2.0</version>
</dependency>
```

**Integration philosophy:** the anti-fraud is an *additional* protection layer. The `FraudClient` has a Circuit Breaker with fallback to `APPROVED` — if the anti-fraud goes down, the game continues normally. No player action is ever blocked due to anti-fraud unavailability.

**Minimal usage example in ragnarok-core:**

```java
// In any Service in ragnarok-core
FraudClient.FraudDecision decision = fraudClient.checkItemTrade(
    playerId, itemId, itemUuid, zenysValue
);

if (decision.isBlocked()) {
    throw new FraudBlockedException(decision.getReason());
}

// Continue normal logic...
```

---

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/ragnarok_antifraude_db` | Full database URL. Override to `localhost:5433` when connecting to Docker from local JVM |
| `DB_PASS` | `postgre` | PostgreSQL password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASS` | (empty) | Redis password (optional) |
| `ANTIFRAUDE_API_KEY` | (empty) | API key for authentication. If empty, filter is disabled (dev mode) |

The service runs on **port 8081** (ragnarok-core uses 8080).

---

## Database

Flyway manages the schema automatically on boot. Two migrations included:

**V1 — Initial schema:** `fraud_audit_log` table (append-only, indexed by player_id, created_at, verdict), `market_prices` table (median prices per item), `player_instance_cooldown` table (cooldowns with unique constraint on player_id + map_id).

**V2 — Price seed:** ~35 items with real prices based on bRO/iRO. Common equipment (Sword 100z), mid-tier (Gae Bolg 200kz), rare cards (Golden Bug 2Mz), MVP cards (Kiel-D-01 30Mz, Thanatos 35Mz). Rule 2 works from the first boot.

`MarketPriceRefresher` syncs prices from PostgreSQL to Redis every 5 minutes. Rules read only from Redis.

---

## Stress Test

The `scripts/stress_test.sh` script simulates real MMO load:

```bash
chmod +x scripts/stress_test.sh

# Default: 1000 requests, 50 concurrent
./scripts/stress_test.sh

# Heavy load
./scripts/stress_test.sh 5000 200

# Extreme stress (detects pool bottlenecks)
./scripts/stress_test.sh 10000 500
```

**Simulated traffic distribution:**

| Scenario | Proportion | Type |
|----------|-----------|------|
| Normal trades | 60% | Clean `ITEM_TRADE` |
| Combat clicks | 15% | `CLICK_ACTION` with varied APS |
| Logins | 10% | `SESSION_LOGIN` from random countries |
| Dupe attacks | 10% | `ITEM_TRADE` with fixed UUID |
| Zeny bombs | 5% | `ITEM_TRADE` with 1B zenys |

**Expected SLA:**

| Metric | Target |
|--------|--------|
| p95 | < 50ms |
| p99 | < 80ms |
| Error rate | < 1% |
| Timeouts (> 5s) | 0 |

The script generates a full report with latencies, verdict distribution, and automatic pass/fail evaluation against the SLA. Prerequisites: `curl`, `jq`, `bc`. Optionally `GNU parallel` for maximum concurrency.

---

## Docker

### Build and run

```bash
# Start everything
docker compose up -d

# Start only infrastructure (avoids port :3000 conflict with front)
docker compose up -d postgres redis

# Follow antifraude logs
docker compose logs -f antifraude

# Stop without losing data
docker compose stop

# Reset everything including volumes (destructive — loses all data)
docker compose down -v
```

### Dockerfile details

Multi-stage build: compilation with JDK 21, runtime with JRE 21 (~200MB). Runs as non-root user (`antifraude`). ZGC with < 1ms pauses. MaxRAMPercentage=75% to adapt to container memory limits. Optimized SecureRandom for fast UUID generation under load.

### Compose services

| Service | Image | External port | Purpose |
|---------|-------|---------------|---------|
| `postgres` | postgres:16-alpine | **5433** | Database (maps 5433→5432 to avoid conflict with ragnarok-core) |
| `redis` | redis:7-alpine | 6379 | State cache, 256MB, allkeys-lru |
| `antifraude` | local build | 8081 | Anti-fraud microservice |
| `prometheus` | prom/prometheus | 9090 | Metrics scraping (every 15s) |
| `grafana` | grafana/grafana | 3000 | Pre-provisioned dashboards (admin/admin) |

> **Port conflict:** Grafana and ragnarok-front both use port 3000. Never run both at the same time. For development, use `docker compose up -d postgres redis` and run the front with `pnpm dev`.

### Observability

- **Prometheus:** http://localhost:9090
- **Grafana:** http://localhost:3000 (admin/admin) — dashboards auto-provisioned
- **Actuator:** http://localhost:8081/actuator/health, `/actuator/prometheus`

---

## Tests

```bash
# Unit tests only (no Docker required)
./mvnw test -Dtest="FraudRulesUnitTest,FraudAnalysisServiceTest"

# Simulation tests (no Docker required)
./mvnw test -Dtest="SimulationControllerTest,SimulationServiceTest,SimulationResultTest"

# Integration tests (requires Docker Desktop running)
./mvnw test -Dtest="FraudControllerIntegrationTest"

# Coverage report (JaCoCo)
./mvnw verify
# Report at: target/site/jacoco/index.html
```

**Test status:**

| Suite | Status | Prerequisite |
|-------|--------|-------------|
| Unit (rules) — 25 tests | ✅ Passing | None |
| Simulation — 6 tests | ✅ Passing | None |
| Integration | ⚠️ Requires Docker Engine | Docker Desktop running |

**Coverage:** `FraudRulesUnitTest` (each rule isolated with mocks, tests approval/blocking/thresholds/fail-open), `FraudAnalysisServiceTest` (parallel engine, timeout, result combination), `FraudControllerIntegrationTest` (full cycle with real databases via Testcontainers).

> **Note:** Unit tests for rules 4 (BotClickSpeed), 6 (CashSecurity), and 7 (InstanceCooldown) are not yet written.

> If `./mvnw clean install` fails with a `TypeTag::UNKNOWN` lock error, stop any running instance of the application before rebuilding.

---

## Security

**Inter-service authentication:** all endpoints (except `/api/fraud/health` and `/swagger-ui`) are protected by `ApiKeyFilter`. ragnarok-core sends the key via the `X-API-Key` header. If `ANTIFRAUDE_API_KEY` is not configured, the filter is disabled (dev mode).

**Fail-open:** the anti-fraud never blocks the game due to its own failure. Timeouts, Redis down, internal exceptions — all result in `APPROVED`.

**Thread safety:** `FraudEvent` and `FraudDecision` are immutable records. Rules are stateless. Zero shared mutable state.

**Secure container:** non-root user, JRE-only (no compiler), no root shell.

---

## Architectural Decisions

| Decision | Reason | Rejected alternative |
|----------|--------|----------------------|
| Separate microservice | Anti-fraud failure doesn't bring down the game | Embedded library — tight coupling |
| Hexagonal | Domain testable without infra, adapters replaceable | Layered — domain coupled to framework |
| Virtual Threads | Zero overhead per I/O-bound thread | Platform threads — pool tuning required |
| Redis for hot state | Sub-millisecond, atomic (SET NX) | Local Caffeine — breaks multi-instance |
| PostgreSQL for cold data | Durable, transactional, full SQL | Persisted Redis — weaker ACID guarantees |
| Async audit | Database off critical path | Synchronous — adds 5–20ms |
| Fail-open everywhere | Game continuity > anti-fraud | Fail-closed — any failure blocks players |
| ZGC | < 1ms pauses | G1GC — 10–50ms pauses under pressure |
| Immutable records | Thread safety by construction | Mutable classes + synchronized |

---

## Project Structure

```
ragnarok-antifraude/
├── CLAUDE.md                              # Context file for Claude Code
├── README.md                              # This file
├── pom.xml                                # Java 21, Spring Boot 3.2.5
├── Dockerfile                             # Multi-stage, JRE 21, ZGC, non-root
├── docker-compose.yml                     # PostgreSQL + Redis + antifraude + Prometheus + Grafana
├── docker/
│   ├── init-multiple-dbs.sh               # Creates both databases in PostgreSQL
│   ├── prometheus.yml                     # Prometheus scrape config
│   └── grafana/                           # Grafana provisioning (datasource + dashboards)
├── scripts/
│   └── stress_test.sh                     # Load test with SLA report
├── integration-core/                      # Files to copy into ragnarok-core
│   ├── FraudClient.java                   # HTTP client + Circuit Breaker (replaces core stub)
│   ├── UsageExamples.java                 # Usage examples in core Services
│   └── application-antifraude.yml         # Resilience4j config for core
└── src/
    ├── main/
    │   ├── java/com/ragnarok/antifraude/
    │   │   ├── RagnarokAntifraude.java
    │   │   ├── domain/
    │   │   │   ├── model/                 # FraudEvent, FraudDecision, enums
    │   │   │   ├── rule/                  # FraudRule interface, RuleResult
    │   │   │   └── port/
    │   │   │       ├── in/                # FraudAnalysisUseCase
    │   │   │       └── out/               # TransactionRepo, PlayerActivityRepo, AuditRepo
    │   │   ├── application/
    │   │   │   ├── service/               # FraudAnalysisService, AuditLogService
    │   │   │   └── rule/                  # 9 FraudRule implementations
    │   │   └── infrastructure/
    │   │       ├── adapter/in/rest/       # Controllers, DTOs, Mapper
    │   │       ├── adapter/out/redis/     # Redis adapters
    │   │       ├── adapter/out/persistence/ # JPA entities, repos, adapter
    │   │       ├── config/                # AsyncConfig, ApiKeyFilter
    │   │       ├── scheduler/             # MarketPriceRefresher
    │   │       └── simulation/            # SimulationService, SimulationResult
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           ├── V1__init_schema.sql
    │           └── V2__seed_market_prices.sql
    └── test/
        ├── java/                          # Unit + Integration tests
        └── resources/application-test.yml
```

---

## Troubleshooting

**Anti-fraud won't start in Docker:**
Check that PostgreSQL and Redis are healthy with `docker compose ps`. The anti-fraud waits for both to be ready before starting (`depends_on: condition: service_healthy`).

**"relation fraud_audit_log does not exist":**
Flyway didn't run. Check that the `ragnarok_antifraude_db` database exists and that `DB_PASS` is correct. If the volume was created before the init script, run `docker compose down -v && docker compose up -d`.

**All decisions are APPROVED even for obvious fraud:**
Check if Redis is accessible. Without Redis, rules 1–4, 6, and 8 fail-open (return APPROVED).

**High latency on stress test:**
Check that Redis is on the same network. Redis network latency > 1ms impacts all rules.

**FraudClient CircuitBreaker (in core) always open:**
Check that the `ANTIFRAUDE_API_KEY` in the core matches the one in the anti-fraud. HTTP 401 counts as a failure for the CircuitBreaker.

**Container marked as unhealthy:**
The HEALTHCHECK uses `wget` on `/api/fraud/health`. If it doesn't respond in 5s, it's marked unhealthy. The `start_period` is 40s to allow Spring Boot to start.

**`TypeTag::UNKNOWN` error during build:**
The JAR file is locked by a running instance. Stop the application before running `./mvnw clean install`.

---

## Known Issues

| Priority | Issue | Location |
|----------|-------|----------|
| 🔴 High | Instance cooldown only persists in Redis — lost on Redis restart | `RedisPlayerActivityAdapter.recordInstanceEntry()` — PostgreSQL write not implemented |
| 🔴 High | `FraudClient` in ragnarok-core is a stub — zero events actually reach this service | `ragnarok-core/.../antifraude/FraudClient.java` — replace with `integration-core/FraudClient.java` |
| 🟡 Medium | `ExecutorService` in parallel engine has no `@PreDestroy` — not closed on shutdown | `FraudAnalysisService` line 51 |
| 🟡 Medium | `networkLatencyMs` from payload is read but never used in analysis | `BotClickSpeedRule` |
| 🟡 Medium | `ApiKeyFilter` uses `String.equals` instead of constant-time comparison (timing attack risk) | `ApiKeyFilter` line 56 |
| 🟡 Medium | `saveRegistrationData` does not persist `emailVerified`/`ageVerified` (only stores document) | `RedisPlayerActivityAdapter` line 160 |
| 🟢 Low | Unit tests missing for rules 4 (BotClickSpeed), 6 (CashSecurity), 7 (InstanceCooldown) | `FraudRulesUnitTest` |

---

## Roadmap

- [ ] Replace `FraudClient` stub in ragnarok-core with `integration-core/FraudClient.java`
- [ ] Persist instance cooldowns to PostgreSQL (source of truth)
- [ ] `@PreDestroy` on `FraudAnalysisService` executor
- [ ] Unit tests for rules 4, 6, and 7
- [ ] Rate limiting per playerId on the `/analyze` endpoint
- [ ] Kafka as an alternative to REST for high-frequency events
- [ ] Machine learning for bot pattern detection
- [ ] Admin web UI (React) for game masters
- [ ] Webhook notifications (Discord/Slack) for CRITICAL blocks
- [ ] Multi-instance support with Redis Cluster
- [ ] Event replay to test new rules against historical traffic
