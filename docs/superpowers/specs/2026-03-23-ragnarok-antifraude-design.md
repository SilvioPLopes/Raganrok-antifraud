# Design Spec — ragnarok-antifraude
Date: 2026-03-23

## Context

Two completely independent microservices running in separate repos, separate IntelliJs, separate GitHub repositories:

- **ragnarok-core** (Java 17, port 8080) — complete game simulator, READ-ONLY from this spec's perspective
- **ragnarok-antifraude** (Java 21, port 8081) — anti-fraud microservice, subject of this spec

Communication is exclusively via HTTP REST. `integration-core/FraudClient.java` is the integration contract — a Spring component that lives inside ragnarok-core and calls ragnarok-antifraude.

## Goal

Complete ragnarok-antifraude as a professional-grade anti-fraud system that:
1. Demonstrates all 9 fraud rules with verifiable behavior
2. Exposes full observability stack (Prometheus + Grafana)
3. Simulates realistic fraud scenarios without requiring ragnarok-core to be running
4. Integrates with ragnarok-core via HTTP when both are running

## Architecture

Hexagonal (Ports & Adapters). Strict dependency rule:
```
domain      → nothing
application → domain only
infrastructure → application + domain
```

Domain layer: zero Spring/Redis/JPA imports. Pure Java records and interfaces.

## Known State of Existing Code

The following items were listed as bugs in CLAUDE.md but ARE ALREADY FIXED in current code:
- `pom.xml`: `java.version` is already `21`
- `docker/init-multiple-dbs.sh` already exists
- `integration-core/FraudClient.java` already uses `RestTemplate` (not WebClient+block), already has UNKNOWN default constructor, already has no try/catch around `analyze()` suppressing the CircuitBreaker

The integration plan must VERIFY these are correct, not re-create them.

## Plan Structure

Four plan files, each mapping to an architectural layer:

```
ragnarok-antifraude/docs/plans/
├── plan-domain.md           # Layer: domain rules + unit tests
├── plan-infrastructure.md   # Layer: Redis, PostgreSQL, Prometheus, Grafana
├── plan-api.md              # Layer: REST endpoints + simulation engine
└── plan-integration.md      # Layer: microservice contract (executed in ragnarok-core)
```

Dependency order: domain and infrastructure are independent (can run in parallel). API depends on both. Integration depends on API.

## Plan: domain

**Scope:** Validate domain layer purity and all 9 rule implementations.

**Tasks:**
1. Assert zero framework imports in all files under `com.ragnarok.antifraude.domain.*` — grep for `import org.springframework`, `import redis`, `import jakarta.persistence` must return zero results
2. Validate `FraudRule` interface contract exists at `domain/rule/FraudRule.java`: methods `ruleId()`, `eventTypes()`, `priority()`, `evaluate(FraudEvent)`
3. Validate all 9 rule implementations in `application/rule/` each have internal try/catch that returns `RuleResult.approved()` on any exception (fail-open)
4. Run `./mvnw test -Dtest="FraudRulesUnitTest" -Djacoco.skip=true -q` — each rule must have approve/block/threshold/fail-open test cases
5. Run `./mvnw test -Dtest="FraudAnalysisServiceTest" -Djacoco.skip=true -q` — parallel execution, 50ms timeout, worst-verdict-wins combination

**Rules coverage:**

| # | Class | EventType | Trigger condition |
|---|-------|-----------|-------------------|
| 1 | AntiDupeRule | ITEM_TRADE | Same itemUuid within 500ms window (Redis SET NX) |
| 2 | DisproportionateTransferRule | ITEM_TRADE | zenysValue > 100x market price from Redis cache |
| 3 | BotFarmTimeRule | FARM_HEARTBEAT | Same map > 6h continuous session in Redis HGET |
| 4 | BotClickSpeedRule | CLICK_ACTION | APS > 15 or stddev < 5ms in Redis sliding window |
| 5 | RegistrationValidationRule | SESSION_LOGIN, ACCOUNT_REGISTRATION | emailVerified=false or ageVerified=false |
| 6 | CashSecurityRule | CASH_PURCHASE | billingDocument != document stored in Redis for player |
| 7 | InstanceCooldownRule | MAP_INSTANCE_ENTRY | Cooldown not expired in PostgreSQL player_instance_cooldown |
| 8 | ImpossibleTravelRule | SESSION_LOGIN | Country changed in < 2h vs Redis HGET last country |
| 9 | MarketMonopolyRule | MARKET_PURCHASE | quantityRequested > 80% of totalInStock (payload math) |

**Success criterion:** Both test suites pass. `grep -r "import org.springframework\|import redis\|import jakarta" src/main/java/com/ragnarok/antifraude/domain/` returns zero results.

---

## Plan: infrastructure

**Scope:** Validate existing infra adapters, add Prometheus + Grafana to the stack.

**Tasks:**

### Verify existing infra
1. Verify `pom.xml` has `<java.version>21</java.version>` — if not, fix it
2. Verify `docker/init-multiple-dbs.sh` exists — if not, create it to create databases `ragnarok_antifraude_db` and `ragnarok_core_db`
3. Verify Flyway migrations exist: `V1__init_schema.sql` (tables: fraud_audit_log, market_prices, player_instance_cooldown), `V2__seed_market_prices.sql` (~35 items with prices)
4. Verify `RedisTransactionAdapter` implements `TransactionRepository` port
5. Verify `RedisPlayerActivityAdapter` implements `PlayerActivityRepository` port
6. Verify `JpaAuditAdapter` implements `AuditRepository` port
7. Verify `MarketPriceRefresher` has `@Scheduled` annotation, reads from `market_prices` table, writes to Redis

### Add Prometheus metrics
8. Add to `pom.xml` under dependencies:
   ```xml
   <dependency>
       <groupId>io.micrometer</groupId>
       <artifactId>micrometer-registry-prometheus</artifactId>
   </dependency>
   ```
9. Add to `application.yml`:
   ```yaml
   management:
     endpoints:
       web:
         exposure:
           include: health,info,prometheus
     endpoint:
       prometheus:
         enabled: true
   ```
10. Add custom Micrometer metrics in `FraudAnalysisService`:
    - `Counter` named `fraud.decisions.total` with tags `verdict` (APPROVED/BLOCKED/CHALLENGE) and `eventType`
    - `Counter` named `fraud.rule.triggered.total` with tag `ruleId`
    - `Timer` named `fraud.processing.duration` (records elapsed ms per analyze call)
    - Inject `MeterRegistry` via constructor
    - Increment counters after `combineResults()`, record timer around the full `analyze()` method

### Add Prometheus + Grafana to Docker Compose
11. Add to `docker-compose.yml`:
    ```yaml
    prometheus:
      image: prom/prometheus:latest
      ports:
        - "9090:9090"
      volumes:
        - ./docker/prometheus.yml:/etc/prometheus/prometheus.yml
      depends_on:
        - antifraude

    grafana:
      image: grafana/grafana:latest
      ports:
        - "3000:3000"
      volumes:
        - ./docker/grafana/provisioning:/etc/grafana/provisioning
      environment:
        - GF_SECURITY_ADMIN_PASSWORD=admin
      depends_on:
        - prometheus
    ```
12. Create `docker/prometheus.yml`:
    ```yaml
    global:
      scrape_interval: 15s
    scrape_configs:
      - job_name: ragnarok-antifraude
        metrics_path: /actuator/prometheus
        static_configs:
          - targets: ['antifraude:8081']
    ```
13. Create `docker/grafana/provisioning/datasources/prometheus.yml`:
    ```yaml
    apiVersion: 1
    datasources:
      - name: Prometheus
        type: prometheus
        url: http://prometheus:9090
        isDefault: true
    ```
14. Create `docker/grafana/provisioning/dashboards/dashboard.yml`:
    ```yaml
    apiVersion: 1
    providers:
      - name: antifraude
        folder: Anti-Fraud
        type: file
        options:
          path: /etc/grafana/provisioning/dashboards
    ```
15. Create `docker/grafana/provisioning/dashboards/antifraude.json` — Grafana dashboard JSON with 4 panels:
    - Panel 1: `fraud_decisions_total` rate by verdict (bar chart, tags: APPROVED green, BLOCKED red, CHALLENGE yellow)
    - Panel 2: `fraud_rule_triggered_total` rate by ruleId (bar chart showing which rules fire most)
    - Panel 3: `fraud_processing_duration` histogram percentiles p50/p95/p99 (time series)
    - Panel 4: `up{job="ragnarok-antifraude"}` (stat panel showing service health)

**Success criterion:** `docker compose up -d` succeeds. `curl http://localhost:8081/actuator/prometheus` returns Prometheus text with `fraud_decisions_total` metric. Grafana at `http://localhost:3000` (admin/admin) shows all 4 panels loading data.

---

## Plan: api

**Scope:** Validate existing controllers, create simulation engine covering all 9 rules.

**Tasks:**

### Verify existing controllers
1. Verify `FraudController` handles `POST /api/fraud/analyze` — accepts `FraudEventRequest`, returns `FraudDecisionResponse`
2. Verify `PlayerStateController` handles all 4 state sync endpoints:
   - `POST /api/fraud/state/player/{id}/map-leave?mapId=`
   - `POST /api/fraud/state/player/{id}/login?countryCode=`
   - `POST /api/fraud/state/player/{id}/instance-enter` (body: mapId, cooldownSeconds)
   - `POST /api/fraud/state/player/{id}/registration` (body: emailVerified, ageVerified, document)
3. Verify `AdminDashboardController` handles `GET /api/fraud/admin/player/{id}/history` and `/blocks?hours=`

### Create SimulationService
4. Create `SimulationResult` record in `application/service/`:
   ```java
   record SimulationResult(
       String scenario,
       int eventsGenerated,
       Map<String, Long> verdictCounts,
       Map<String, Long> triggeredRules,
       long durationMs
   ) {}
   ```
5. Create `SimulationService` in `application/service/`:
   - Constructor injects `FraudAnalysisUseCase` (the port interface at `domain/port/in/FraudAnalysisUseCase.java`) and `RedisPlayerActivityAdapter` (for pre-seeding state when scenarios require it)
   - Method `simulate(String scenario, int count)` → `SimulationResult`
   - Dispatches to private method per scenario name
   - Aggregates verdict counts and triggered rules from each `FraudDecision` returned

6. Implement 10 simulation scenarios: 1 baseline (`normal-traffic`) + 9 rule-specific (one per fraud rule):
   - **`normal-traffic`** (rules baseline): 100 events mixing ITEM_TRADE (valid UUIDs, normal prices), CLICK_ACTION (APS=5, stddev=50ms), SESSION_LOGIN (emailVerified=true, same country). Expected: >95% APPROVED.
   - **`item-dupe`** (rule 1): 20 pairs of ITEM_TRADE with identical itemUuid sent in rapid succession. Expected: second of each pair is BLOCKED by ANTI_DUPE.
   - **`disproportionate-transfer`** (rule 2): ITEM_TRADE events with zenysValue=50_000_000 for itemId=501 (Red Herb, market price ~50z from V2 seed). Ratio >> 100x. Expected: BLOCKED by DISPROPORTIONATE_TRANSFER.
   - **`bot-farm`** (rule 3): Pre-seed Redis with farm session start timestamp of 7 hours ago for a fake playerId via `RedisPlayerActivityAdapter`. Then send FARM_HEARTBEAT for same player/map. Expected: BLOCKED/CHALLENGE by BOT_FARM_TIME.
   - **`bot-attack`** (rule 4): CLICK_ACTION with actionsPerSecond=30.0, networkLatencyMs=1.0 (stddev effectively 0). Expected: BLOCKED by BOT_CLICK_SPEED.
   - **`unregistered-account`** (rule 5): SESSION_LOGIN and ACCOUNT_REGISTRATION events with emailVerified=false or ageVerified=false. Expected: BLOCKED by REGISTRATION_VALIDATION.
   - **`cash-fraud`** (rule 6): Pre-seed Redis with document "111.111.111-11" for fake playerId. Send CASH_PURCHASE with billingDocument="999.999.999-99". Expected: BLOCKED by CASH_SECURITY.
   - **`instance-spam`** (rule 7): Pre-seed PostgreSQL `player_instance_cooldown` table with an active cooldown (expiry in future) for fake playerId + mapId. Send MAP_INSTANCE_ENTRY for same player/map. Expected: BLOCKED by INSTANCE_COOLDOWN.
   - **`impossible-travel`** (rule 8): Pre-seed Redis with last login country="BR" and timestamp 30 minutes ago for fake playerId. Send SESSION_LOGIN with countryCode="JP". Expected: BLOCKED by IMPOSSIBLE_TRAVEL.
   - **`market-monopoly`** (rule 9): MARKET_PURCHASE with quantityRequested=950, totalInStock=1000 (95% of stock). Expected: BLOCKED/FLAG by MARKET_MONOPOLY.

### Create SimulationController
7. Create `SimulationController` in `infrastructure/adapter/in/rest/`:
   ```java
   @RestController
   @RequestMapping("/api/simulate")
   public class SimulationController {
       @PostMapping("/{scenario}")
       public SimulationResultResponse simulate(
           @PathVariable String scenario,
           @RequestParam(defaultValue = "50") int count) { ... }
   }
   ```
8. Create `SimulationResultResponse` DTO mirroring `SimulationResult` fields (for JSON serialization)

**Success criterion:** `POST /api/simulate/bot-attack?count=20` returns response with `verdictCounts.BLOCKED > 0`. `POST /api/simulate/item-dupe?count=10` returns BLOCKED count > 0. Each scenario demonstrates its target rule triggering. Metrics panels in Grafana spike after simulation runs.

---

## Plan: integration

**Scope:** Wire ragnarok-core to call ragnarok-antifraude via HTTP. Executed in the **ragnarok-core** repo (separate IntelliJ, separate terminal).

**Pre-condition:** ragnarok-antifraude running at `http://localhost:8081`. Run `curl http://localhost:8081/api/fraud/health` — must return 200.

### Verify FraudClient (already corrected)
1. Open `integration-core/FraudClient.java` in ragnarok-antifraude repo. Verify:
   - `analyze()` method has NO try/catch — exceptions propagate to `@CircuitBreaker`
   - Default constructor sets `verdict = "UNKNOWN"` (not APPROVED)
   - Sync calls use `RestTemplate` (not `WebClient.block()`)
   - `WebClient` only used for fire-and-forget void methods (`notifyMapLeaveAsync`, etc.)
   - Method `syncRegistrationAsync(Long playerId, boolean emailVerified, boolean ageVerified, String document)` exists
   - Fallback method `fallbackApproved(Exception ex)` logs exactly: `"Anti-fraud circuit breaker open — fail-open (APPROVED). Cause: {}"`
   - If any of these conditions fail, fix them before proceeding

2. Copy `integration-core/FraudClient.java` → ragnarok-core at `src/main/java/com/ragnarok/infrastructure/antifraude/FraudClient.java`

### Configure ragnarok-core
3. Verify ragnarok-core `pom.xml` already has `resilience4j-spring-boot3` (it does — used by `RathenaDownloadService`). No new dependency needed for RestTemplate (included in `spring-boot-starter-web`). Do NOT add `spring-boot-starter-webflux` — ragnarok-core is a Java 17 MVC app and WebClient is only used for fire-and-forget calls in FraudClient which is acceptable.

4. Merge `integration-core/application-antifraude.yml` into ragnarok-core `src/main/resources/application.yml`:
   - Add `antifraude.url`, `antifraude.api-key` properties
   - Add Resilience4j circuit breaker config for `antifraude` instance (slidingWindowSize=5, failureRateThreshold=50, waitDurationInOpenState=10s, timeoutDuration=150ms)

### Wire FraudClient into services

Before each wiring step, verify the target class and method name exists in ragnarok-core by reading the file. The expected locations based on current ragnarok-core code:
- `BattleService` → `src/main/java/com/ragnarok/application/service/BattleService.java`, method `realizarAtaque(Long playerId, Long monsterId)`
- `BattleEventHandler` → `src/main/java/com/ragnarok/application/service/BattleEventHandler.java`, method `onMonsterKilled(MonsterKilledEvent event)`
- `MapService` → `src/main/java/com/ragnarok/application/service/MapService.java`, method `travel(Long playerId, String destination)`
- `PlayerService` or `PlayerController` → `src/main/java/com/ragnarok/api/controller/PlayerController.java`

If any class or method name differs from the above, adapt the wiring to match the actual code.

5. Wire in `BattleService.realizarAtaque()`:
   - Add `FraudClient fraudClient` to constructor
   - Before damage calculation: call `fraudClient.checkClickSpeed(playerId, 1.0, 100.0)` (default values — real APS not tracked in core)
   - If `decision.isBlocked()` and `decision.dropSession()` → throw `GameException("Action blocked by anti-fraud")`
   - After monster kill (before `eventPublisher.publishEvent`): call `fraudClient.checkFarmHeartbeat(playerId, mapId)` — get mapId from playerEntity

6. Wire in `BattleEventHandler.onMonsterKilled()`:
   - Add `FraudClient fraudClient` to constructor
   - For each item in loot: call `fraudClient.checkItemTrade(playerId, itemId, UUID.randomUUID().toString(), 0L)`
   - If `decision.isBlocked()` → log warning, skip item (do not throw — event handler must not break the transaction)

7. Wire in `MapService.travel()`:
   - Add `FraudClient fraudClient` to constructor
   - Before saving player: call `fraudClient.checkInstanceEntry(playerId, destination)`
   - If `decision.isBlocked()` → throw `GameException("Travel blocked by anti-fraud")`
   - After saving player: call `fraudClient.notifyMapLeaveAsync(playerId, currentMap)`

8. Wire in `PlayerService` or `PlayerController` for player creation:
   - On `POST /api/players`: call `fraudClient.syncRegistrationAsync(playerId, false, false, null)` (default unverified)
   - This seeds the registration state in antifraude for the player

### Validate circuit breaker fallback
9. Stop ragnarok-antifraude. Call `POST /api/battle/attack` in ragnarok-core 6 times. After 5 failures the circuit breaker opens and subsequent calls must return game results normally (not throw errors). Confirm ragnarok-core logs contain the exact string: `Anti-fraud circuit breaker open — fail-open (APPROVED). Cause:` (this comes from `FraudClient.fallbackApproved()` verified in step 1). This validates that antifraude unavailability never breaks the game.

10. Start ragnarok-antifraude again. Call `POST /api/battle/attack`. Confirm a new row appears in `fraud_audit_log` table of ragnarok-antifraude PostgreSQL.

**ragnarok-core endpoint reference (READ-ONLY):**
```
POST   /api/players                          → create player {name, jobClass}
GET    /api/players/{id}                     → get player state
POST   /api/battle/attack                    → {playerId, monsterId}
POST   /api/players/{id}/map/walk            → random encounter
POST   /api/players/{id}/map/travel          → {destination}
GET    /api/players/{id}/inventory           → list items
POST   /api/players/{id}/inventory/{id}/use  → use/equip item
GET    /api/players/{id}/skills              → list skills
POST   /api/players/{id}/skills/{id}/learn   → learn skill
```

Core runs port 8080. Antifraude port 8081. No shared database. No shared code.

**Success criterion:** With both services running:
- `POST /api/battle/attack` in core Swagger → row in `fraud_audit_log` + Grafana metric counter increments
- Circuit breaker opens when antifraude is down → game continues normally
- `POST /api/players/{id}/map/travel` with a recently-entered instance map → BLOCKED response from core
