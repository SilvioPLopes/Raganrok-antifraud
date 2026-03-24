# Microservice Integration Plan (ragnarok-core ← ragnarok-antifraude)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **EXECUTED IN ragnarok-core REPO.** Open ragnarok-core in IntelliJ. This plan lives in ragnarok-antifraude for reference — when executing, the working directory is ragnarok-core.

**Goal:** Wire `FraudClient` into ragnarok-core so that game actions (attack, item grant, map travel, player creation) automatically call ragnarok-antifraude, with circuit breaker ensuring the game never fails due to antifraude unavailability.

**Architecture:** `FraudClient` (already written in ragnarok-antifraude's `integration-core/`) uses `RestTemplate` for synchronous fraud checks and `WebClient` for fire-and-forget state sync. Each public fraud check method is annotated `@CircuitBreaker(name = "antifraude")` with a fallback that returns `APPROVED` — game continues normally if antifraude is down.

**Tech Stack:** Java 17, Spring Boot 3.4.2, Resilience4j (already present in core), RestTemplate (from spring-boot-starter-web)

**Pre-conditions:**
1. ragnarok-antifraude is running at `http://localhost:8081`
2. Run `curl http://localhost:8081/api/fraud/health` — must return HTTP 200
3. This plan does NOT add `spring-boot-starter-webflux` — `WebClient` for fire-and-forget is acceptable in a Java 17 MVC app. RestTemplate handles all synchronous calls.

**Spec (reference):** `ragnarok-antifraude/docs/superpowers/specs/2026-03-23-ragnarok-antifraude-design.md`

**Dependency:** Run after plan-infrastructure from ragnarok-antifraude (metrics must be wired so Grafana shows the integration traffic).

---

## File Map (all paths relative to ragnarok-core root)

```
VERIFY (before copying):
  <ragnarok-antifraude>/integration-core/FraudClient.java   ← source file to copy
  <ragnarok-antifraude>/integration-core/application-antifraude.yml

READ (to adapt wiring):
  src/main/java/com/ragnarok/application/service/BattleService.java
  src/main/java/com/ragnarok/application/service/BattleEventHandler.java
  src/main/java/com/ragnarok/application/service/MapService.java
  src/main/java/com/ragnarok/api/controller/PlayerController.java

CREATE:
  src/main/java/com/ragnarok/infrastructure/antifraude/FraudClient.java

MODIFY:
  pom.xml                                  add spring-boot-starter-webflux
  src/main/resources/application.yml       add antifraude config + Resilience4j CB
  src/main/java/.../service/BattleService.java
  src/main/java/.../service/BattleEventHandler.java
  src/main/java/.../service/MapService.java
  src/main/java/.../api/controller/PlayerController.java
```

---

### Task 1: Verify FraudClient before copying

**Files:**
- Read: `<ragnarok-antifraude>/integration-core/FraudClient.java`

- [ ] **Step 1: Verify FraudClient correctness checklist**

Open `integration-core/FraudClient.java` from the ragnarok-antifraude repo. Confirm ALL of the following:

| # | Check | What to look for |
|---|-------|-----------------|
| 1 | No try/catch around `analyze()` | `analyze()` is a private method. It must NOT have try/catch — exceptions must propagate to `@CircuitBreaker` |
| 2 | UNKNOWN default constructor | `FraudDecision()` no-arg constructor sets `this.verdict = "UNKNOWN"` |
| 3 | RestTemplate for sync calls | `analyze()` uses `restTemplate.postForObject(...)`, NOT `webClient...block()` |
| 4 | WebClient only for fire-and-forget | `notifyMapLeaveAsync`, `notifyInstanceEntryAsync`, `syncRegistrationAsync`, `notifyLoginAsync` use WebClient with `.subscribe()` |
| 5 | `syncRegistrationAsync(Long, boolean, boolean, String)` exists | Method signature must match exactly |
| 6 | Fallback log message | `fallbackApproved()` logs exactly: `"Anti-fraud circuit breaker open — fail-open (APPROVED). Cause: {}"` |

If any check fails, fix `FraudClient.java` in the antifraude repo before copying.

---

### Task 2: Copy FraudClient and add WebFlux dependency

**Files:**
- Create: `src/main/java/com/ragnarok/infrastructure/antifraude/FraudClient.java`
- Modify: `pom.xml`

- [ ] **Step 1: Create the antifraude package directory and copy FraudClient**

Copy the file content from `ragnarok-antifraude/integration-core/FraudClient.java` to:
`src/main/java/com/ragnarok/infrastructure/antifraude/FraudClient.java`

The package declaration at the top of the file must be:
```java
package com.ragnarok.infrastructure.antifraude;
```

Confirm the package line matches. Fix it if it says something else (e.g., `com.ragnarok.infrastructure.antifraude` is correct).

- [ ] **Step 2: Read pom.xml to find dependencies section**

Find the `<dependencies>` block. Confirm `resilience4j-spring-boot3` is already present (used by `RathenaDownloadService`). It is — no need to add it again.

- [ ] **Step 3: Add spring-boot-starter-webflux for WebClient (fire-and-forget only)**

`FraudClient` uses `WebClient` for fire-and-forget void methods. WebClient requires WebFlux on the classpath.

**Server auto-configuration note:** In Spring Boot 3.x, adding `spring-boot-starter-webflux` to a project that already has `spring-boot-starter-web` does NOT switch the embedded server from Tomcat to Netty. Spring Boot detects both and keeps the Servlet stack (Tomcat). Verify this is true by confirming the existing `pom.xml` already contains `spring-boot-starter-web` before adding WebFlux.

Add after the existing `spring-boot-starter-web` dependency:

```xml
<!-- WebClient for fire-and-forget async calls to antifraude state sync endpoints.
     Does NOT switch server to Netty — Tomcat stays as Servlet container. -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

After adding, run `./mvnw spring-boot:run --dry-run 2>&1 | grep -i "netty\|tomcat"` or check startup logs for `Tomcat started on port(s): 8080` to confirm Tomcat is still the server. If Netty appears, add `spring.main.web-application-type=servlet` to `application.yml`.

- [ ] **Step 4: Verify build compiles**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS. If FraudClient has import errors, read the error, fix the import (likely missing `reactor.core.publisher.Mono` — already covered by webflux).

---

### Task 3: Configure antifraude connection in application.yml

**Files:**
- Modify: `src/main/resources/application.yml`
- Read: `<ragnarok-antifraude>/integration-core/application-antifraude.yml`

- [ ] **Step 1: Read application-antifraude.yml from antifraude repo**

This file contains the exact Resilience4j configuration. Copy its contents.

- [ ] **Step 2: Merge into ragnarok-core application.yml**

Add at the end of `application.yml`:

```yaml
# ── Anti-Fraud microservice ─────────────────────────────────────────────
antifraude:
  url: http://localhost:8081
  api-key: ${ANTIFRAUDE_API_KEY:}

resilience4j:
  circuitbreaker:
    instances:
      antifraude:
        slidingWindowSize: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 2
        automaticTransitionFromOpenToHalfOpenEnabled: true
  timelimiter:
    instances:
      antifraude:
        timeoutDuration: 150ms
```

Note: If `application-antifraude.yml` has different values, use those values instead.

---

### Task 4: Wire FraudClient in BattleService

**Files:**
- Read: `src/main/java/com/ragnarok/application/service/BattleService.java`
- Modify: `src/main/java/com/ragnarok/application/service/BattleService.java`

- [ ] **Step 1: Read BattleService.java**

Confirm:
- Class: `BattleService`
- Method: `realizarAtaque(Long playerId, Long monsterId)` — returns `String`
- Constructor: takes `PlayerRepository`, `MonsterRepository`, `PlayerMapper`, `MonsterMapper`, `BattleEngine`, `WeaponSizeService`, `ApplicationEventPublisher`
- The player's current map is available via `playerEntity.getMapName()`

- [ ] **Step 2: Add FraudClient to BattleService**

Add `FraudClient fraudClient` field. Add it as the last constructor parameter and assign `this.fraudClient = fraudClient;`.

Add import: `import com.ragnarok.infrastructure.antifraude.FraudClient;`

- [ ] **Step 3: Add click speed check before damage calculation**

In `realizarAtaque()`, immediately after loading `playerEntity` and before the dead-player check, add:

```java
// Anti-fraud: check for bot behavior (click speed)
FraudClient.FraudDecision fraudDecision = fraudClient.checkClickSpeed(
    playerId,
    1.0,    // APS placeholder — core does not track actual APS
    100.0   // network latency placeholder
);
if (fraudDecision.isBlocked() && fraudDecision.dropSession()) {
    log.warn("Battle blocked by anti-fraud for player {}: {}", playerId, fraudDecision.getReason());
    throw new GameException("Action blocked by anti-fraud: " + fraudDecision.getReason());
}
```

Add import: `import com.ragnarok.domain.exception.GameException;` (already exists in the project).

- [ ] **Step 4: Add farm heartbeat after determining current map**

After `Player player = playerMapper.toDomain(playerEntity);`, add:

```java
// Anti-fraud: report farm heartbeat (async, non-blocking)
String currentMap = playerEntity.getMapName() != null ? playerEntity.getMapName() : "prontera";
fraudClient.checkFarmHeartbeat(playerId, currentMap);
```

Note: `checkFarmHeartbeat` is annotated `@CircuitBreaker` and returns a `FraudDecision`. Ignore the result here — this is a heartbeat for bot detection, not a blocking check. The decision is logged and audited by antifraude.

---

### Task 5: Wire FraudClient in BattleEventHandler

**Files:**
- Read: `src/main/java/com/ragnarok/application/service/BattleEventHandler.java`
- Modify: `src/main/java/com/ragnarok/application/service/BattleEventHandler.java`

- [ ] **Step 1: Read BattleEventHandler.java**

Confirm:
- Method: `onMonsterKilled(MonsterKilledEvent event)` — has `@EventListener @Transactional`
- It persists loot items via some service/repository
- `MonsterKilledEvent` has fields: `playerId`, `monsterId`, `List<Item> loot`, `baseExp`, `jobExp`

- [ ] **Step 2: Add FraudClient to BattleEventHandler constructor**

Add `FraudClient fraudClient` field and constructor injection.

Add import: `import com.ragnarok.infrastructure.antifraude.FraudClient;`

- [ ] **Step 3: Add item trade check for each loot item**

In `onMonsterKilled()`, after the loot items are saved to the player (find the persistence call for loot items), add:

```java
// Anti-fraud: check each dropped item for trade anomalies
if (event.loot() != null) {
    event.loot().forEach(item -> {
        FraudClient.FraudDecision decision = fraudClient.checkItemTrade(
            event.playerId(),
            (long) item.getId(),
            java.util.UUID.randomUUID().toString(), // each drop gets a unique UUID
            0L  // drops have no zeny value (not a trade)
        );
        if (decision.isBlocked()) {
            log.warn("Item drop flagged by anti-fraud for player {}, item {}: {}",
                event.playerId(), item.getName(), decision.getReason());
            // Non-blocking: log and continue. Do not throw — event handler must not break the transaction.
        }
    });
}
```

Note: `item.getId()` — check the `Item` domain model for the correct id method. If `Item` is a record with `int id()`, use `(long) item.id()`. Read `domain/model/Item.java` to confirm.

---

### Task 6: Wire FraudClient in MapService

**Files:**
- Read: `src/main/java/com/ragnarok/application/service/MapService.java`
- Modify: `src/main/java/com/ragnarok/application/service/MapService.java`

- [ ] **Step 1: Read MapService.java**

Confirm:
- Method: `travel(Long playerId, String destination)` — `@Transactional`, returns void
- It reads `currentMap` from `player.getMapName()` before setting new map

- [ ] **Step 2: Add FraudClient to MapService**

Add `FraudClient fraudClient` field and constructor injection.

Add import: `import com.ragnarok.infrastructure.antifraude.FraudClient;`

- [ ] **Step 3: Add instance entry check in travel()**

In `travel()`, after validating the destination portal is available and before `player.setMapName(destination)`, add:

```java
// Anti-fraud: check instance entry (cooldown enforcement)
FraudClient.FraudDecision fraudDecision = fraudClient.checkInstanceEntry(playerId, destination);
if (fraudDecision.isBlocked()) {
    throw new GameException("Travel blocked: " + fraudDecision.getReason());
}
```

- [ ] **Step 4: Add map leave notification after travel()**

After `playerRepository.save(player)`, add:

```java
// Anti-fraud: notify map leave for farm session tracking (fire-and-forget)
fraudClient.notifyMapLeaveAsync(playerId, currentMap);
```

Note: `currentMap` is already declared earlier in the method (before setting destination).

---

### Task 7: Wire FraudClient in PlayerController for registration

**Files:**
- Read: `src/main/java/com/ragnarok/api/controller/PlayerController.java`
- Modify: `src/main/java/com/ragnarok/api/controller/PlayerController.java`

- [ ] **Step 1: Read PlayerController.java**

Confirm:
- Method handling `POST /api/players` — takes `CreatePlayerRequestDTO`, creates player, returns `PlayerResponseDTO`
- The new player's `id` is available in the response

- [ ] **Step 2: Add FraudClient to PlayerController**

Add `FraudClient fraudClient` field and constructor injection.

Add import: `import com.ragnarok.infrastructure.antifraude.FraudClient;`

- [ ] **Step 3: Notify antifraude of new player registration**

After the player is created and its `id` is available, add:

```java
// Anti-fraud: register player state (fire-and-forget — does not affect game)
fraudClient.syncRegistrationAsync(response.getId(), false, false, null);
```

Where `response.getId()` is the new player's ID. Adapt to the actual field name of the created player's ID in `PlayerResponseDTO`.

---

### Task 8: Validate full integration

- [ ] **Step 1: Build ragnarok-core**

```bash
./mvnw clean package -DskipTests -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Start ragnarok-core (with antifraude already running)**

In IntelliJ, run `RagnarokCoreApplication`. It starts on port 8080.

Confirm both services are up:
```bash
curl -s http://localhost:8081/api/fraud/health   # antifraude: should return 200
curl -s http://localhost:8080/api/players         # ragnarok-core: should return 200 with player list
```

Expected: 200 with player list (empty or existing players).

- [ ] **Step 3: Create a player**

```bash
curl -s -X POST http://localhost:8080/api/players \
  -H "Content-Type: application/json" \
  -d '{"name":"TestPlayer","jobClass":"NOVICE"}'
```

Expected: 201 or 200 with player JSON including `id`. Note the `id`.

- [ ] **Step 4: Walk to find a monster**

```bash
curl -s -X POST http://localhost:8080/api/players/1/map/walk
```

Repeat until `encounterOccurred: true` and note `monsterId`.

- [ ] **Step 5: Attack the monster**

```bash
curl -s -X POST http://localhost:8080/api/battle/attack \
  -H "Content-Type: application/json" \
  -d '{"playerId":1,"monsterId":<monsterId>}'
```

Expected: game response (damage dealt). This call must have triggered `fraudClient.checkClickSpeed()` and `fraudClient.checkFarmHeartbeat()` inside BattleService.

- [ ] **Step 6: Verify audit log entry in antifraude PostgreSQL**

Connect to the antifraude database:
```bash
docker exec -it <postgres-container-name> psql -U postgres -d ragnarok_antifraude_db \
  -c "SELECT event_type, verdict, created_at FROM fraud_audit_log ORDER BY created_at DESC LIMIT 5;"
```

Expected: rows with `event_type = 'CLICK_ACTION'` or `'FARM_HEARTBEAT'`, `verdict = 'APPROVED'` (no fraud detected for normal play).

- [ ] **Step 7: Verify Grafana shows integration traffic**

Open `http://localhost:3000`. The "Fraud Decisions — Rate by Verdict" panel should show APPROVED spikes corresponding to the battle actions.

- [ ] **Step 8: Test circuit breaker fallback**

Stop ragnarok-antifraude (`docker compose stop antifraude`).

Call `POST /api/battle/attack` 6 times. The first 5 calls may be slow (RestTemplate timeout 150ms from Resilience4j). After 5 failures, the circuit breaker opens — subsequent calls return immediately.

In ragnarok-core logs, confirm the line:
```
Anti-fraud circuit breaker open — fail-open (APPROVED). Cause:
```

Restart antifraude: `docker compose start antifraude`. After ~10s (waitDurationInOpenState), the circuit closes and the next battle call sends a real fraud check again.

- [ ] **Step 9: Commit ragnarok-core changes**

```bash
git add src/main/java/com/ragnarok/infrastructure/antifraude/ \
    src/main/java/com/ragnarok/application/service/BattleService.java \
    src/main/java/com/ragnarok/application/service/BattleEventHandler.java \
    src/main/java/com/ragnarok/application/service/MapService.java \
    src/main/java/com/ragnarok/api/controller/PlayerController.java \
    src/main/resources/application.yml \
    pom.xml
git commit -m "feat(antifraude): wire FraudClient into BattleService, MapService, PlayerController"
```

---

Plan complete when:
1. `POST /api/battle/attack` on core → row in `fraud_audit_log` on antifraude
2. Grafana shows live traffic from core actions
3. Circuit breaker test passes — game works normally when antifraude is down
