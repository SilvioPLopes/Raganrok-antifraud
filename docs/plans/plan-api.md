# API & Simulation Engine Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify existing REST controllers are correct, then create a simulation engine (`SimulationService` + `SimulationController`) that generates synthetic fraud events for all 10 scenarios (1 baseline + 9 rule-specific) and processes them through the live fraud analysis pipeline.

**Architecture:** `SimulationService` lives in `infrastructure/simulation/` because it requires direct Redis access to pre-seed state for stateful rules (Rules 3, 6, 7, 8). This is intentional — simulation is an infrastructure concern (a test driver), not business logic. It injects `FraudAnalysisUseCase` (the domain port) and `RedisTemplate` (for state seeding). `SimulationController` is a standard REST adapter. After simulation runs, Prometheus counters spike and Grafana dashboard shows the fraud patterns.

**Tech Stack:** Java 21, Spring Boot 3.2.5, Spring Data Redis, JUnit 5, Mockito

**Spec:** `docs/superpowers/specs/2026-03-23-ragnarok-antifraude-design.md`

**Dependency:** Run after plan-infrastructure passes (Prometheus metrics must be wired in FraudAnalysisService first).

---

## File Map

```
VERIFY (read-only):
  src/main/java/.../adapter/in/rest/FraudController.java
  src/main/java/.../adapter/in/rest/PlayerStateController.java
  src/main/java/.../adapter/in/rest/AdminDashboardController.java
  src/main/java/.../application/rule/BotFarmTimeRule.java       ← read to find Redis key format
  src/main/java/.../application/rule/ImpossibleTravelRule.java  ← read to find Redis key format
  src/main/java/.../application/rule/CashSecurityRule.java      ← read to find Redis key format
  src/main/java/.../adapter/out/redis/RedisPlayerActivityAdapter.java ← read to find Redis key format
  src/main/resources/db/migration/V1__init_schema.sql           ← read player_instance_cooldown schema

CREATE:
  src/main/java/.../infrastructure/simulation/SimulationResult.java
  src/main/java/.../infrastructure/simulation/SimulationService.java
  src/main/java/.../adapter/in/rest/dto/SimulationResultResponse.java
  src/main/java/.../adapter/in/rest/SimulationController.java

TEST:
  src/test/java/.../infrastructure/simulation/SimulationServiceTest.java
  src/test/java/.../adapter/in/rest/SimulationControllerTest.java
```

Full package prefix: `com.ragnarok.antifraude`

---

### Task 1: Verify existing REST controllers

**Files:**
- Read: `src/main/java/com/ragnarok/antifraude/infrastructure/adapter/in/rest/FraudController.java`
- Read: `src/main/java/com/ragnarok/antifraude/infrastructure/adapter/in/rest/PlayerStateController.java`
- Read: `src/main/java/com/ragnarok/antifraude/infrastructure/adapter/in/rest/AdminDashboardController.java`

- [ ] **Step 1: Verify FraudController**

Confirm:
- `@RestController`, `@RequestMapping("/api/fraud")`
- `POST /api/fraud/analyze` accepts `FraudEventRequest`, returns `FraudDecisionResponse`
- Calls `FraudAnalysisUseCase.analyze()`
- Returns HTTP 200 with response body

- [ ] **Step 2: Verify PlayerStateController**

Confirm all 4 state sync endpoints exist:
- `POST /api/fraud/state/player/{id}/map-leave?mapId=`
- `POST /api/fraud/state/player/{id}/login?countryCode=`
- `POST /api/fraud/state/player/{id}/instance-enter` (body: `{ mapId, cooldownSeconds }`)
- `POST /api/fraud/state/player/{id}/registration` (body: `{ emailVerified, ageVerified, document }`)

- [ ] **Step 3: Verify AdminDashboardController**

Confirm:
- `GET /api/fraud/admin/player/{id}/history?limit=N`
- `GET /api/fraud/admin/player/{id}/blocks?hours=N`

- [ ] **Step 4: Run integration test to confirm controllers work end-to-end**

```bash
./mvnw test -Dtest="FraudControllerIntegrationTest" -Djacoco.skip=true -q
```

Expected: BUILD SUCCESS. If it fails, read the failure and fix the controller or its wiring.

---

### Task 2: Read rule implementations to discover Redis key formats

These key formats are required to correctly pre-seed state in SimulationService.

**Files:**
- Read: `src/main/java/com/ragnarok/antifraude/application/rule/BotFarmTimeRule.java`
- Read: `src/main/java/com/ragnarok/antifraude/application/rule/ImpossibleTravelRule.java`
- Read: `src/main/java/com/ragnarok/antifraude/application/rule/CashSecurityRule.java`
- Read: `src/main/java/com/ragnarok/antifraude/adapter/out/redis/RedisPlayerActivityAdapter.java`
- Read: `src/main/resources/db/migration/V1__init_schema.sql`

- [ ] **Step 1: Find BotFarmTimeRule Redis key for farm session**

Look for the Redis key used to store the farm session start time. It will be something like:
- `"farm:session:" + playerId + ":" + mapId`
- or delegated to `PlayerActivityRepository.getFarmSessionStart()`

Note the exact key format — you will need it in SimulationService to pre-seed state.

- [ ] **Step 2: Find ImpossibleTravelRule Redis key for last login**

Look for the Redis key used to store last login country + timestamp. It will be something like:
- `"login:country:" + playerId`
- or a hash key with fields `country` and `timestamp`

Note the exact key format and value format (e.g., `"BR:1700000000"` or separate fields).

- [ ] **Step 3: Find CashSecurityRule Redis key for registered document**

Look for the Redis key that stores the player's registered CPF/CNPJ. It will be something like:
- `"player:document:" + playerId`

Note the exact key format.

- [ ] **Step 4: Find player_instance_cooldown table structure**

Read V1 migration. Find the `player_instance_cooldown` table. Note columns: player_id, map_id, cooldown_until (timestamp).

---

### Task 3: Create SimulationResult record

**Files:**
- Create: `src/main/java/com/ragnarok/antifraude/infrastructure/simulation/SimulationResult.java`

- [ ] **Step 1: Write failing test first**

Create `src/test/java/com/ragnarok/antifraude/infrastructure/simulation/SimulationServiceTest.java`:

```java
package com.ragnarok.antifraude.infrastructure.simulation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SimulationResultTest {

    @Test
    void simulationResult_holdsAllFields() {
        var result = new SimulationResult(
            "bot-attack",
            50,
            java.util.Map.of("APPROVED", 5L, "BLOCKED", 45L),
            java.util.Map.of("BOT_CLICK_SPEED", 45L),
            120L
        );

        assertThat(result.scenario()).isEqualTo("bot-attack");
        assertThat(result.eventsGenerated()).isEqualTo(50);
        assertThat(result.verdictCounts().get("BLOCKED")).isEqualTo(45L);
        assertThat(result.triggeredRules().get("BOT_CLICK_SPEED")).isEqualTo(45L);
        assertThat(result.durationMs()).isEqualTo(120L);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw test -Dtest="SimulationResultTest" -Djacoco.skip=true -q 2>&1 | tail -5
```

Expected: compile error — `SimulationResult` does not exist yet.

- [ ] **Step 3: Create SimulationResult**

```java
package com.ragnarok.antifraude.infrastructure.simulation;

import java.util.Map;

/**
 * Holds the aggregated results of a simulation run.
 * Lives in infrastructure because simulation is a test/demo driver, not business logic.
 */
public record SimulationResult(
    String scenario,
    int eventsGenerated,
    Map<String, Long> verdictCounts,
    Map<String, Long> triggeredRules,
    long durationMs
) {}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./mvnw test -Dtest="SimulationResultTest" -Djacoco.skip=true -q
```

Expected: BUILD SUCCESS.

---

### Task 4: Create SimulationService with all 10 scenarios

**Files:**
- Create: `src/main/java/com/ragnarok/antifraude/infrastructure/simulation/SimulationService.java`
- Modify: `src/test/java/com/ragnarok/antifraude/infrastructure/simulation/SimulationServiceTest.java`

> **Pre-step — discover InstanceCooldown JPA repository name before writing any code:**
> Read `src/main/java/com/ragnarok/antifraude/application/rule/InstanceCooldownRule.java`.
> Find which JPA repository it uses to check active cooldowns (e.g., `PlayerInstanceCooldownRepository`).
> Note the exact class name — it is required in the `SimulationService` constructor and tests below.
> If the rule uses a port interface (not a JPA repository directly), find the interface name in `domain/port/out/`.
> This must be done before writing the constructor.

- [ ] **Step 1: Write failing tests**

Add to `SimulationServiceTest.java`:

```java
package com.ragnarok.antifraude.infrastructure.simulation;

import com.ragnarok.antifraude.domain.model.FraudDecision;
import com.ragnarok.antifraude.domain.model.RequiredAction;
import com.ragnarok.antifraude.domain.model.RiskLevel;
import com.ragnarok.antifraude.domain.model.Verdict;
import com.ragnarok.antifraude.domain.port.in.FraudAnalysisUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationServiceTest {

    @Mock FraudAnalysisUseCase fraudAnalysisUseCase;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    // Add @Mock for the cooldown repository discovered in the pre-step above.
    // Example: @Mock PlayerInstanceCooldownRepository cooldownRepository;
    // Replace the type and variable name with the actual class found in InstanceCooldownRule.java.

    SimulationService simulationService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // Pass cooldownRepository as the third constructor argument:
        // simulationService = new SimulationService(fraudAnalysisUseCase, redisTemplate, cooldownRepository);
        // Replace with the actual constructor signature once the pre-step is done.
        simulationService = new SimulationService(fraudAnalysisUseCase, redisTemplate /*, cooldownRepository */);
    }

    @Test
    void simulate_botAttack_returnsBlockedVerdicts() {
        // Arrange: mock analyze to return BLOCKED for CLICK_ACTION events
        FraudDecision blocked = new FraudDecision(
            "evt-1", 9999L, Verdict.BLOCKED, RequiredAction.CANCEL_ACTION,
            RiskLevel.HIGH, List.of("BOT_CLICK_SPEED"), "APS too high", 3L, null
        );
        when(fraudAnalysisUseCase.analyze(any())).thenReturn(blocked);

        // Act
        SimulationResult result = simulationService.simulate("bot-attack", 10);

        // Assert
        assertThat(result.scenario()).isEqualTo("bot-attack");
        assertThat(result.eventsGenerated()).isEqualTo(10);
        assertThat(result.verdictCounts().getOrDefault("BLOCKED", 0L)).isGreaterThan(0);
        assertThat(result.triggeredRules().getOrDefault("BOT_CLICK_SPEED", 0L)).isGreaterThan(0);
    }

    @Test
    void simulate_normalTraffic_returnsApprovedVerdicts() {
        FraudDecision approved = new FraudDecision(
            "evt-1", 9999L, Verdict.APPROVED, RequiredAction.NONE,
            RiskLevel.LOW, List.of(), "All rules passed", 5L, null
        );
        when(fraudAnalysisUseCase.analyze(any())).thenReturn(approved);

        SimulationResult result = simulationService.simulate("normal-traffic", 20);

        assertThat(result.eventsGenerated()).isEqualTo(20);
        assertThat(result.verdictCounts().getOrDefault("APPROVED", 0L)).isEqualTo(20L);
    }

    @Test
    void simulate_unknownScenario_throwsIllegalArgument() {
        assertThatThrownBy(() -> simulationService.simulate("unknown-scenario", 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown-scenario");
    }
}
```

Note: adapt `FraudDecision` constructor to match the actual constructor signature found in `FraudDecision.java`. Read the file if needed.

- [ ] **Step 2: Run tests to verify they fail**

```bash
./mvnw test -Dtest="SimulationServiceTest" -Djacoco.skip=true -q 2>&1 | tail -10
```

Expected: compile error — `SimulationService` does not exist.

- [ ] **Step 3: Create SimulationService**

Before writing this class, confirm the exact Redis key formats found in Task 2. Replace the placeholder key strings in the code below with the actual keys discovered.

```java
package com.ragnarok.antifraude.infrastructure.simulation;

import com.ragnarok.antifraude.domain.model.FraudDecision;
import com.ragnarok.antifraude.domain.model.FraudEvent;
import com.ragnarok.antifraude.domain.port.in.FraudAnalysisUseCase;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Simulation driver — lives in infrastructure because it needs Redis access for state seeding.
 * This is NOT domain logic; it is a test/demo utility.
 */
@Service
public class SimulationService {

    private final FraudAnalysisUseCase fraudAnalysisUseCase;
    private final RedisTemplate<String, String> redisTemplate;

    // Fake player IDs used for simulation — never clash with real game data
    private static final long SIM_PLAYER_BASE = 900_000L;

    // Add the cooldown repository field discovered in the pre-step.
    // Example: private final PlayerInstanceCooldownRepository cooldownRepository;

    public SimulationService(FraudAnalysisUseCase fraudAnalysisUseCase,
                             RedisTemplate<String, String> redisTemplate
                             /*, <CooldownRepositoryType> cooldownRepository */) {
        this.fraudAnalysisUseCase = fraudAnalysisUseCase;
        this.redisTemplate = redisTemplate;
        // this.cooldownRepository = cooldownRepository;
    }

    public SimulationResult simulate(String scenario, int count) {
        long start = System.currentTimeMillis();
        List<FraudDecision> decisions = switch (scenario) {
            case "normal-traffic"          -> runNormalTraffic(count);
            case "item-dupe"               -> runItemDupe(count);
            case "disproportionate-transfer" -> runDisproportionateTransfer(count);
            case "bot-farm"                -> runBotFarm(count);
            case "bot-attack"              -> runBotAttack(count);
            case "unregistered-account"    -> runUnregisteredAccount(count);
            case "cash-fraud"              -> runCashFraud(count);
            case "instance-spam"           -> runInstanceSpam(count);
            case "impossible-travel"       -> runImpossibleTravel(count);
            case "market-monopoly"         -> runMarketMonopoly(count);
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        };
        return aggregate(scenario, decisions, System.currentTimeMillis() - start);
    }

    // ── Scenarios ──────────────────────────────────────────────────────────

    private List<FraudDecision> runNormalTraffic(int count) {
        List<FraudDecision> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            FraudEvent event = switch (i % 3) {
                case 0 -> buildEvent(SIM_PLAYER_BASE + i, "ITEM_TRADE",
                    Map.of("itemId", 501L, "itemUuid", UUID.randomUUID().toString(), "zenysValue", 100L));
                case 1 -> buildEvent(SIM_PLAYER_BASE + i, "CLICK_ACTION",
                    Map.of("actionsPerSecond", 5.0, "networkLatencyMs", 80.0));
                default -> buildEvent(SIM_PLAYER_BASE + i, "SESSION_LOGIN", "127.0.0.1", "BR",
                    Map.of("emailVerified", true, "ageVerified", true));
            };
            results.add(fraudAnalysisUseCase.analyze(event));
        }
        return results;
    }

    private List<FraudDecision> runItemDupe(int count) {
        // Send pairs: same UUID twice — second triggers ANTI_DUPE
        List<FraudDecision> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String sharedUuid = UUID.randomUUID().toString(); // same UUID for both in pair
            FraudEvent first  = buildEvent(SIM_PLAYER_BASE + i, "ITEM_TRADE",
                Map.of("itemId", 4324L, "itemUuid", sharedUuid, "zenysValue", 25_000_000L));
            FraudEvent second = buildEvent(SIM_PLAYER_BASE + i, "ITEM_TRADE",
                Map.of("itemId", 4324L, "itemUuid", sharedUuid, "zenysValue", 25_000_000L));
            results.add(fraudAnalysisUseCase.analyze(first));
            results.add(fraudAnalysisUseCase.analyze(second)); // this one should be BLOCKED
        }
        return results;
    }

    private List<FraudDecision> runDisproportionateTransfer(int count) {
        // itemId=501 is Red Herb, seeded in V2 with price ~50z
        // zenysValue = 50_000_000 = 50M / 50 = 1_000_000x market price → BLOCKED
        List<FraudDecision> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            FraudEvent event = buildEvent(SIM_PLAYER_BASE + i, "ITEM_TRADE",
                Map.of("itemId", 501L, "itemUuid", UUID.randomUUID().toString(), "zenysValue", 50_000_000L));
            results.add(fraudAnalysisUseCase.analyze(event));
        }
        return results;
    }

    private List<FraudDecision> runBotFarm(int count) {
        // PRE-SEED: set farm session start to 7 hours ago in Redis
        // KEY FORMAT: replace "farm:session:{playerId}:{mapId}" with actual key found in BotFarmTimeRule
        // Read BotFarmTimeRule.java and RedisPlayerActivityAdapter.java to confirm the exact key format
        String mapId = "prt_maze03";
        long sevenHoursAgo = Instant.now().minus(7, ChronoUnit.HOURS).getEpochSecond();

        List<FraudDecision> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long playerId = SIM_PLAYER_BASE + 3_000 + i;
            // Seed farm session in Redis — use ACTUAL key format from BotFarmTimeRule
            String redisKey = "farm:session:" + playerId + ":" + mapId; // ← verify this key format
            redisTemplate.opsForValue().set(redisKey, String.valueOf(sevenHoursAgo));

            FraudEvent event = buildEvent(playerId, "FARM_HEARTBEAT", Map.of("mapId", mapId));
            results.add(fraudAnalysisUseCase.analyze(event));
        }
        return results;
    }

    private List<FraudDecision> runBotAttack(int count) {
        // APS=30, latency stddev effectively 0 → triggers BOT_CLICK_SPEED
        List<FraudDecision> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            FraudEvent event = buildEvent(SIM_PLAYER_BASE + i, "CLICK_ACTION",
                Map.of("actionsPerSecond", 30.0, "networkLatencyMs", 1.0));
            results.add(fraudAnalysisUseCase.analyze(event));
        }
        return results;
    }

    private List<FraudDecision> runUnregisteredAccount(int count) {
        List<FraudDecision> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            FraudEvent event = buildEvent(SIM_PLAYER_BASE + 5_000 + i, "ACCOUNT_REGISTRATION",
                Map.of("emailVerified", false, "ageVerified", false));
            results.add(fraudAnalysisUseCase.analyze(event));
        }
        return results;
    }

    private List<FraudDecision> runCashFraud(int count) {
        // PRE-SEED: register document "111.111.111-11" for player in Redis
        // KEY FORMAT: read CashSecurityRule.java and RedisPlayerActivityAdapter.java for exact key
        List<FraudDecision> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long playerId = SIM_PLAYER_BASE + 6_000 + i;
            // Seed registered document — use ACTUAL key format from CashSecurityRule
            String docKey = "player:document:" + playerId; // ← verify this key format
            redisTemplate.opsForValue().set(docKey, "111.111.111-11");

            FraudEvent event = buildEvent(playerId, "CASH_PURCHASE",
                Map.of("billingName", "Hacker User", "billingDocument", "999.999.999-99"));
            results.add(fraudAnalysisUseCase.analyze(event));
        }
        return results;
    }

    private List<FraudDecision> runInstanceSpam(int count) {
        // PRE-STEP REQUIRED: read InstanceCooldownRule.java before implementing this method.
        // Find the JPA repository (or port) used to check active cooldowns.
        // Add it as a constructor field (already guided in the pre-step comment above the constructor).
        //
        // Once you have the repository, pre-seed a cooldown row with expiry in the future:
        //
        // Example (adapt entity/method to actual class names found):
        //   PlayerInstanceCooldownEntity entity = new PlayerInstanceCooldownEntity();
        //   entity.setPlayerId(playerId);
        //   entity.setMapId(mapId);
        //   entity.setCooldownUntil(Instant.now().plus(10, ChronoUnit.MINUTES));
        //   cooldownRepository.save(entity);
        //
        // Then send MAP_INSTANCE_ENTRY event — rule finds active cooldown → BLOCKED.

        List<FraudDecision> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long playerId = SIM_PLAYER_BASE + 7_000 + i;
            String mapId = "mvp_room01";

            // INSERT COOLDOWN PRE-SEED HERE using the repository discovered in pre-step.
            // See comment block above for the pattern.

            FraudEvent event = buildEvent(playerId, "MAP_INSTANCE_ENTRY", Map.of("mapId", mapId));
            results.add(fraudAnalysisUseCase.analyze(event));
        }
        return results;
    }

    private List<FraudDecision> runImpossibleTravel(int count) {
        // PRE-SEED: set last login to BR, 30 minutes ago
        // KEY FORMAT: read ImpossibleTravelRule.java for exact Redis key
        List<FraudDecision> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long playerId = SIM_PLAYER_BASE + 8_000 + i;
            long thirtyMinutesAgo = Instant.now().minus(30, ChronoUnit.MINUTES).getEpochSecond();
            // Seed last login — use ACTUAL key format from ImpossibleTravelRule
            String loginKey = "login:country:" + playerId; // ← verify this key format
            redisTemplate.opsForValue().set(loginKey, "BR:" + thirtyMinutesAgo);

            FraudEvent event = buildEvent(playerId, "SESSION_LOGIN", "200.100.50.1", "JP",
                Map.of("emailVerified", true, "ageVerified", true));
            results.add(fraudAnalysisUseCase.analyze(event));
        }
        return results;
    }

    private List<FraudDecision> runMarketMonopoly(int count) {
        // 95% of stock → triggers MARKET_MONOPOLY
        List<FraudDecision> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            FraudEvent event = buildEvent(SIM_PLAYER_BASE + i, "MARKET_PURCHASE",
                Map.of("itemId", 4324L, "quantityRequested", 950L, "totalInStock", 1000L));
            results.add(fraudAnalysisUseCase.analyze(event));
        }
        return results;
    }

    // ── Builders & aggregation ─────────────────────────────────────────────

    private FraudEvent buildEvent(long playerId, String eventType, Map<String, Object> payload) {
        return buildEvent(playerId, eventType, "127.0.0.1", "BR", payload);
    }

    private FraudEvent buildEvent(long playerId, String eventType,
                                   String ipAddress, String countryCode,
                                   Map<String, Object> payload) {
        // Adapt constructor call to match FraudEvent record definition
        // Read src/main/java/.../domain/model/FraudEvent.java for the exact constructor
        return new FraudEvent(
            UUID.randomUUID().toString(),
            eventType,                    // or EventType.valueOf(eventType) if it's an enum
            playerId,
            ipAddress,
            countryCode,
            Instant.now(),
            payload
        );
    }

    private SimulationResult aggregate(String scenario, List<FraudDecision> decisions, long durationMs) {
        Map<String, Long> verdictCounts = new HashMap<>();
        Map<String, Long> triggeredRules = new HashMap<>();

        for (FraudDecision d : decisions) {
            String verdict = d.verdict().name();
            verdictCounts.merge(verdict, 1L, Long::sum);
            if (d.triggeredRules() != null) {
                d.triggeredRules().forEach(ruleId -> triggeredRules.merge(ruleId, 1L, Long::sum));
            }
        }

        return new SimulationResult(scenario, decisions.size(), verdictCounts, triggeredRules, durationMs);
    }
}
```

**IMPORTANT:** Before submitting this file, read `FraudEvent.java` to verify:
1. The constructor signature — adapt `buildEvent()` to match exactly
2. Whether `eventType` is a `String` or an `Enum` — if enum, use `EventType.valueOf(eventType)`

Also read `InstanceCooldownRule.java` and complete the `runInstanceSpam()` method based on how that rule checks the database.

- [ ] **Step 4: Run tests to verify they pass**

```bash
./mvnw test -Dtest="SimulationServiceTest" -Djacoco.skip=true -q
```

Expected: BUILD SUCCESS.

---

### Task 5: Create SimulationController and DTO

**Files:**
- Create: `src/main/java/com/ragnarok/antifraude/infrastructure/adapter/in/rest/dto/SimulationResultResponse.java`
- Create: `src/main/java/com/ragnarok/antifraude/infrastructure/adapter/in/rest/SimulationController.java`
- Create: `src/test/java/com/ragnarok/antifraude/adapter/in/rest/SimulationControllerTest.java`

- [ ] **Step 1: Write failing controller test**

```java
package com.ragnarok.antifraude.adapter.in.rest;

import com.ragnarok.antifraude.infrastructure.simulation.SimulationResult;
import com.ragnarok.antifraude.infrastructure.simulation.SimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SimulationController.class)
class SimulationControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean SimulationService simulationService;

    @Test
    void postSimulate_returnsOkWithResult() throws Exception {
        when(simulationService.simulate(any(), anyInt())).thenReturn(
            new SimulationResult("bot-attack", 10,
                Map.of("BLOCKED", 10L), Map.of("BOT_CLICK_SPEED", 10L), 45L)
        );

        mockMvc.perform(post("/api/simulate/bot-attack").param("count", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scenario").value("bot-attack"))
            .andExpect(jsonPath("$.eventsGenerated").value(10))
            .andExpect(jsonPath("$.verdictCounts.BLOCKED").value(10))
            .andExpect(jsonPath("$.durationMs").isNumber());
    }

    @Test
    void postSimulate_unknownScenario_returns400() throws Exception {
        when(simulationService.simulate(any(), anyInt()))
            .thenThrow(new IllegalArgumentException("Unknown scenario: bad-scenario"));

        mockMvc.perform(post("/api/simulate/bad-scenario"))
            .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw test -Dtest="SimulationControllerTest" -Djacoco.skip=true -q 2>&1 | tail -10
```

Expected: compile error.

- [ ] **Step 3: Create SimulationResultResponse DTO**

```java
package com.ragnarok.antifraude.infrastructure.adapter.in.rest.dto;

import com.ragnarok.antifraude.infrastructure.simulation.SimulationResult;
import java.util.Map;

public record SimulationResultResponse(
    String scenario,
    int eventsGenerated,
    Map<String, Long> verdictCounts,
    Map<String, Long> triggeredRules,
    long durationMs
) {
    public static SimulationResultResponse from(SimulationResult result) {
        return new SimulationResultResponse(
            result.scenario(),
            result.eventsGenerated(),
            result.verdictCounts(),
            result.triggeredRules(),
            result.durationMs()
        );
    }
}
```

- [ ] **Step 4: Create SimulationController**

```java
package com.ragnarok.antifraude.infrastructure.adapter.in.rest;

import com.ragnarok.antifraude.infrastructure.adapter.in.rest.dto.SimulationResultResponse;
import com.ragnarok.antifraude.infrastructure.simulation.SimulationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/simulate")
public class SimulationController {

    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    /**
     * Run a named fraud simulation scenario.
     *
     * Scenarios: normal-traffic, item-dupe, disproportionate-transfer, bot-farm,
     *            bot-attack, unregistered-account, cash-fraud, instance-spam,
     *            impossible-travel, market-monopoly
     *
     * @param scenario name of the scenario
     * @param count    number of events to generate (default: 50)
     */
    @PostMapping("/{scenario}")
    public ResponseEntity<SimulationResultResponse> simulate(
            @PathVariable String scenario,
            @RequestParam(defaultValue = "50") int count) {

        var result = simulationService.simulate(scenario, count);
        return ResponseEntity.ok(SimulationResultResponse.from(result));
    }
}
```

- [ ] **Step 5: Add IllegalArgumentException mapping to GlobalExceptionHandler**

Read `src/main/java/com/ragnarok/antifraude/infrastructure/config/` or wherever `GlobalExceptionHandler` lives. Verify it handles `IllegalArgumentException` with HTTP 400. If missing, add:

```java
@ExceptionHandler(IllegalArgumentException.class)
public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
    return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
}
```

- [ ] **Step 6: Run controller test to verify it passes**

```bash
./mvnw test -Dtest="SimulationControllerTest" -Djacoco.skip=true -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/ragnarok/antifraude/infrastructure/simulation/ \
    src/main/java/com/ragnarok/antifraude/infrastructure/adapter/in/rest/SimulationController.java \
    src/main/java/com/ragnarok/antifraude/infrastructure/adapter/in/rest/dto/SimulationResultResponse.java \
    src/test/java/
git commit -m "feat(simulation): add SimulationController with 10 fraud scenarios"
```

---

### Task 6: End-to-end validation with running services

Requires: `docker compose up -d` from plan-infrastructure is running.

- [ ] **Step 1: Run bot-attack scenario**

```bash
curl -s -X POST "http://localhost:8081/api/simulate/bot-attack?count=20" | python -m json.tool
```

Expected response shape:
```json
{
  "scenario": "bot-attack",
  "eventsGenerated": 20,
  "verdictCounts": { "BLOCKED": 20 },
  "triggeredRules": { "BOT_CLICK_SPEED": 20 },
  "durationMs": <number>
}
```

- [ ] **Step 2: Run item-dupe scenario**

```bash
curl -s -X POST "http://localhost:8081/api/simulate/item-dupe?count=5" | python -m json.tool
```

Expected: `verdictCounts.BLOCKED > 0`, `triggeredRules.ANTI_DUPE > 0`.

- [ ] **Step 3: Run market-monopoly scenario**

```bash
curl -s -X POST "http://localhost:8081/api/simulate/market-monopoly?count=10" | python -m json.tool
```

Expected: `triggeredRules.MARKET_MONOPOLY > 0`.

- [ ] **Step 4: Run unregistered-account scenario**

```bash
curl -s -X POST "http://localhost:8081/api/simulate/unregistered-account?count=10" | python -m json.tool
```

Expected: `triggeredRules.REGISTRATION_VALIDATION > 0`.

- [ ] **Step 5: Verify metrics in Grafana**

After running all scenarios, open Grafana (`http://localhost:3000`). The "Rules Triggered" panel should show spikes for `BOT_CLICK_SPEED`, `ANTI_DUPE`, `MARKET_MONOPOLY`, `REGISTRATION_VALIDATION`.

- [ ] **Step 6: Run full test suite**

```bash
./mvnw test -Djacoco.skip=true -q
```

Expected: BUILD SUCCESS, all tests pass.

Plan complete when all scenarios return non-zero BLOCKED counts for their target rules and Grafana shows the metric spikes.
