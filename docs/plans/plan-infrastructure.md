# Infrastructure & Observability Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify existing infrastructure adapters, add Prometheus metrics to FraudAnalysisService, and expand docker-compose with Prometheus + Grafana for full observability.

**Architecture:** Adds `micrometer-registry-prometheus` to expose metrics at `/actuator/prometheus`. FraudAnalysisService records 3 metrics: decision counter (by verdict + eventType), rule trigger counter (by ruleId), processing duration timer. Docker Compose gains two new services: Prometheus (scrapes antifraude every 15s) and Grafana (pre-provisioned dashboard showing verdicts, rule triggers, latency percentiles, service health).

**Tech Stack:** Java 21, Spring Boot 3.2.5, Micrometer, Prometheus, Grafana, Docker Compose

**Spec:** `docs/superpowers/specs/2026-03-23-ragnarok-antifraude-design.md`

**Dependency:** Run after plan-domain passes.

---

## File Map

```
VERIFY (read-only):
  pom.xml
  docker/init-multiple-dbs.sh
  src/main/resources/db/migration/V1__init_schema.sql
  src/main/resources/db/migration/V2__seed_market_prices.sql
  src/main/java/.../adapter/out/redis/RedisTransactionAdapter.java
  src/main/java/.../adapter/out/redis/RedisPlayerActivityAdapter.java
  src/main/java/.../adapter/out/persistence/JpaAuditAdapter.java
  src/main/java/.../scheduler/MarketPriceRefresher.java

MODIFY:
  pom.xml                                          add micrometer-registry-prometheus
  src/main/resources/application.yml               add actuator/prometheus config
  src/main/java/.../application/service/FraudAnalysisService.java   add MeterRegistry + 3 metrics
  docker-compose.yml                               add prometheus + grafana services
  src/test/java/.../application/FraudAnalysisServiceTest.java       inject SimpleMeterRegistry

CREATE:
  docker/prometheus.yml                            scrape config
  docker/grafana/provisioning/datasources/prometheus.yml
  docker/grafana/provisioning/dashboards/dashboard.yml
  docker/grafana/provisioning/dashboards/antifraude.json
```

---

### Task 1: Verify existing infrastructure

**Files:**
- Read: `pom.xml`
- Read: `docker/init-multiple-dbs.sh`
- Read: `src/main/resources/db/migration/V1__init_schema.sql`

- [ ] **Step 1: Verify java.version is 21**

Read `pom.xml`. Find `<java.version>`. Must be `21`. If it is `17`, change it to `21`.

- [ ] **Step 2: Verify init-multiple-dbs.sh exists and creates both databases**

Read `docker/init-multiple-dbs.sh`. It must create BOTH `ragnarok_antifraude_db` AND `ragnarok_core_db`. If either is missing, update the script to:

```bash
#!/bin/bash
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    SELECT 'CREATE DATABASE ragnarok_antifraude_db'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'ragnarok_antifraude_db')\gexec

    SELECT 'CREATE DATABASE ragnarok_core_db'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'ragnarok_core_db')\gexec
EOSQL
```

Both databases are needed: antifraude uses `ragnarok_antifraude_db` for audit logs and cooldowns; ragnarok-core uses `ragnarok_core_db` when both services share the same PostgreSQL container.

- [ ] **Step 3: Verify V1 migration creates required tables**

Read `V1__init_schema.sql`. Confirm it creates:
- `fraud_audit_log` (columns: id, event_id, player_id, verdict, risk_level, triggered_rules, reason, processing_time_ms, created_at)
- `market_prices` (columns: item_id, median_price, updated_at)
- `player_instance_cooldown` (columns: id, player_id, map_id, cooldown_until, unique constraint on player_id + map_id)

- [ ] **Step 4: Verify Redis adapters implement their ports**

Read `RedisTransactionAdapter.java`. Confirm `implements TransactionRepository`.
Read `RedisPlayerActivityAdapter.java`. Confirm `implements PlayerActivityRepository`.
Read `JpaAuditAdapter.java`. Confirm `implements AuditRepository`.

- [ ] **Step 5: Verify MarketPriceRefresher**

Read `MarketPriceRefresher.java`. Confirm `@Scheduled` annotation is present, reads from `market_prices` table, writes to Redis.

---

### Task 2: Add Prometheus metrics dependency

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Read pom.xml**

Find the dependencies section.

- [ ] **Step 2: Add micrometer-registry-prometheus**

After the existing `spring-boot-starter-actuator` dependency, add:

```xml
<!-- ══════ Prometheus metrics ══════ -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

No version needed — Spring Boot manages the version.

- [ ] **Step 3: Verify build compiles**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS.

---

### Task 3: Configure Actuator to expose Prometheus endpoint

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Read application.yml**

Find existing `management:` or `actuator:` section, or note its absence.

- [ ] **Step 2: Add actuator configuration**

Add (merge with existing management section if present):

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    prometheus:
      enabled: true
    health:
      show-details: always
```

- [ ] **Step 3: Verify endpoint accessible (requires running service)**

After docker compose is up later, verify with:
```bash
curl -s http://localhost:8081/actuator/prometheus | head -20
```

---

### Task 4: Add Micrometer metrics to FraudAnalysisService (TDD)

**Files:**
- Modify: `src/test/java/com/ragnarok/antifraude/application/FraudAnalysisServiceTest.java`
- Modify: `src/main/java/com/ragnarok/antifraude/application/service/FraudAnalysisService.java`

- [ ] **Step 1: Read FraudAnalysisServiceTest.java**

Understand how the service is constructed in tests — specifically how `FraudAnalysisService` is instantiated. Note all constructor arguments currently used.

- [ ] **Step 2: Read FraudAnalysisService.java**

Note the current constructor signature and the `analyze()` method structure. Find exactly where `combineResults()` is called and where `auditLogService.logAsync()` is called — metrics recording goes between these two calls.

- [ ] **Step 3: Add failing test for metrics recording**

In `FraudAnalysisServiceTest.java`, add import:
```java
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.Counter;
```

Add `SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();` field.

Update the `FraudAnalysisService` constructor call in `setUp()` (or wherever the service is instantiated) to pass `meterRegistry` as the last argument.

Add test method:
```java
@Test
void analyze_recordsDecisionCounterMetric() {
    // Arrange: create a FraudEvent for an eventType that has at least one applicable rule
    // Use the simplest event type available — check which eventType has the fewest rules
    // Then call analyze()
    FraudDecision decision = service.analyze(/* a valid FraudEvent */);

    // Assert: counter was incremented
    Counter counter = meterRegistry.find("fraud.decisions.total").counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isGreaterThan(0);
}

@Test
void analyze_recordsProcessingDurationMetric() {
    service.analyze(/* a valid FraudEvent */);

    assertThat(meterRegistry.find("fraud.processing.duration").timer()).isNotNull();
    assertThat(meterRegistry.find("fraud.processing.duration").timer().count()).isGreaterThan(0);
}
```

- [ ] **Step 4: Run test to verify it fails**

```bash
./mvnw test -Dtest="FraudAnalysisServiceTest" -Djacoco.skip=true -q 2>&1 | tail -20
```

Expected: compilation error or test failure because `FraudAnalysisService` constructor does not yet accept `MeterRegistry`.

- [ ] **Step 5: Modify FraudAnalysisService to accept MeterRegistry**

Add import:
```java
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
```

Add field:
```java
private final MeterRegistry meterRegistry;
```

Add `MeterRegistry meterRegistry` as last constructor parameter. Assign `this.meterRegistry = meterRegistry;`.

- [ ] **Step 6: Record metrics in analyze() method**

After `FraudDecision decision = combineResults(event, results, elapsed);` and before `auditLogService.logAsync(event, decision);`, add:

```java
// Metrics: decision counter
meterRegistry.counter("fraud.decisions.total",
    "verdict", decision.verdict().name(),
    "eventType", event.eventType().toString()
).increment();

// Metrics: rule trigger counters
decision.triggeredRules().forEach(ruleId ->
    meterRegistry.counter("fraud.rule.triggered.total", "ruleId", ruleId).increment()
);

// Metrics: processing duration
meterRegistry.timer("fraud.processing.duration")
    .record(Duration.ofMillis(elapsed));
```

Note: `event.eventType()` — if eventType is an enum, use `.name()`; if it is a String, use it directly. Read `FraudEvent.java` to confirm the type.

- [ ] **Step 7: Run tests to verify they pass**

```bash
./mvnw test -Dtest="FraudAnalysisServiceTest" -Djacoco.skip=true -q
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 8: Commit**

```bash
git add pom.xml src/main/resources/application.yml \
    src/main/java/com/ragnarok/antifraude/application/service/FraudAnalysisService.java \
    src/test/java/com/ragnarok/antifraude/application/FraudAnalysisServiceTest.java
git commit -m "feat(metrics): add Prometheus metrics to FraudAnalysisService"
```

---

### Task 5: Expand docker-compose with Prometheus and Grafana

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Read docker-compose.yml**

Note the existing services (postgres, redis, antifraude) and the network configuration.

- [ ] **Step 2: Add prometheus and grafana services**

Append to the `services:` section (before the closing of the file):

```yaml
  prometheus:
    image: prom/prometheus:v2.51.0
    container_name: prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./docker/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    networks:
      - ragnarok-net
    depends_on:
      antifraude:
        condition: service_healthy

  grafana:
    image: grafana/grafana:10.4.2
    container_name: grafana
    ports:
      - "3000:3000"
    volumes:
      - ./docker/grafana/provisioning:/etc/grafana/provisioning:ro
      - grafana-data:/var/lib/grafana
    environment:
      - GF_SECURITY_ADMIN_USER=admin
      - GF_SECURITY_ADMIN_PASSWORD=admin
      - GF_AUTH_ANONYMOUS_ENABLED=false
    networks:
      - ragnarok-net
    depends_on:
      - prometheus
```

Add `grafana-data:` to the `volumes:` section at the bottom of docker-compose.yml (alongside existing named volumes, if any).

Note: If the network is named differently than `ragnarok-net`, use the actual network name found in Step 1.

---

### Task 6: Create Prometheus scrape configuration

**Files:**
- Create: `docker/prometheus.yml`

- [ ] **Step 1: Create prometheus.yml**

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: ragnarok-antifraude
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['antifraude:8081']
    scrape_interval: 10s
```

Note: `antifraude` is the container name from docker-compose.yml. If the service name differs, use the actual service name.

---

### Task 7: Create Grafana provisioning files

**Files:**
- Create: `docker/grafana/provisioning/datasources/prometheus.yml`
- Create: `docker/grafana/provisioning/dashboards/dashboard.yml`

- [ ] **Step 1: Create datasource provisioning**

Create `docker/grafana/provisioning/datasources/prometheus.yml`:

```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    uid: prometheus
    url: http://prometheus:9090
    access: proxy
    isDefault: true
    editable: false
```

- [ ] **Step 2: Create dashboard provider provisioning**

Create `docker/grafana/provisioning/dashboards/dashboard.yml`:

```yaml
apiVersion: 1

providers:
  - name: antifraude
    orgId: 1
    folder: Anti-Fraud
    folderUid: antifraude
    type: file
    disableDeletion: false
    editable: true
    options:
      path: /etc/grafana/provisioning/dashboards
```

---

### Task 8: Create Grafana dashboard JSON

**Files:**
- Create: `docker/grafana/provisioning/dashboards/antifraude.json`

- [ ] **Step 1: Create dashboard JSON with 4 panels**

```json
{
  "annotations": { "list": [] },
  "editable": true,
  "graphTooltip": 1,
  "id": null,
  "panels": [
    {
      "id": 1,
      "title": "Fraud Decisions — Rate by Verdict",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "targets": [
        {
          "expr": "sum by (verdict) (rate(fraud_decisions_total[1m]))",
          "legendFormat": "{{verdict}}",
          "refId": "A"
        }
      ],
      "fieldConfig": {
        "defaults": {
          "custom": { "lineWidth": 2 },
          "unit": "reqps"
        },
        "overrides": [
          { "matcher": { "id": "byName", "options": "APPROVED" }, "properties": [{ "id": "color", "value": { "fixedColor": "green", "mode": "fixed" } }] },
          { "matcher": { "id": "byName", "options": "BLOCKED" },  "properties": [{ "id": "color", "value": { "fixedColor": "red",   "mode": "fixed" } }] },
          { "matcher": { "id": "byName", "options": "CHALLENGE" },"properties": [{ "id": "color", "value": { "fixedColor": "orange","mode": "fixed" } }] }
        ]
      },
      "options": { "tooltip": { "mode": "multi" } }
    },
    {
      "id": 2,
      "title": "Rules Triggered — Rate by Rule ID",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "targets": [
        {
          "expr": "sum by (ruleId) (rate(fraud_rule_triggered_total[1m]))",
          "legendFormat": "{{ruleId}}",
          "refId": "A"
        }
      ],
      "fieldConfig": {
        "defaults": { "unit": "reqps", "custom": { "lineWidth": 2 } }
      }
    },
    {
      "id": 3,
      "title": "Processing Duration Percentiles (ms)",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 8 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "targets": [
        {
          "expr": "histogram_quantile(0.50, sum by (le) (rate(fraud_processing_duration_seconds_bucket[1m]))) * 1000",
          "legendFormat": "p50",
          "refId": "A"
        },
        {
          "expr": "histogram_quantile(0.95, sum by (le) (rate(fraud_processing_duration_seconds_bucket[1m]))) * 1000",
          "legendFormat": "p95",
          "refId": "B"
        },
        {
          "expr": "histogram_quantile(0.99, sum by (le) (rate(fraud_processing_duration_seconds_bucket[1m]))) * 1000",
          "legendFormat": "p99",
          "refId": "C"
        }
      ],
      "fieldConfig": {
        "defaults": { "unit": "ms", "custom": { "lineWidth": 2 } },
        "overrides": [
          { "matcher": { "id": "byName", "options": "p99" }, "properties": [{ "id": "color", "value": { "fixedColor": "red", "mode": "fixed" } }] }
        ]
      }
    },
    {
      "id": 4,
      "title": "Service Health",
      "type": "stat",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 8 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "targets": [
        {
          "expr": "up{job=\"ragnarok-antifraude\"}",
          "legendFormat": "Anti-Fraud Service",
          "refId": "A"
        }
      ],
      "fieldConfig": {
        "defaults": {
          "mappings": [
            { "type": "value", "options": { "0": { "text": "DOWN", "color": "red" }, "1": { "text": "UP", "color": "green" } } }
          ],
          "thresholds": { "mode": "absolute", "steps": [{ "color": "red", "value": 0 }, { "color": "green", "value": 1 }] }
        }
      },
      "options": { "reduceOptions": { "calcs": ["lastNotNull"] }, "orientation": "auto", "textMode": "auto", "colorMode": "background" }
    }
  ],
  "refresh": "10s",
  "schemaVersion": 39,
  "tags": ["ragnarok", "antifraude"],
  "time": { "from": "now-15m", "to": "now" },
  "timepicker": {},
  "title": "Ragnarok Anti-Fraud Dashboard",
  "uid": "ragnarok-antifraude-v1",
  "version": 1
}
```

---

### Task 9: Start full stack and validate

- [ ] **Step 1: Build the application**

```bash
./mvnw clean package -DskipTests -q
```

Expected: BUILD SUCCESS, JAR created in `target/`.

- [ ] **Step 2: Start all services**

```bash
docker compose up -d
```

Expected: all services start without errors.

- [ ] **Step 3: Verify all containers are healthy**

```bash
docker compose ps
```

Expected: postgres, redis, antifraude, prometheus, grafana all show `Up` or `healthy`.

- [ ] **Step 4: Verify Prometheus scraping**

```bash
curl -s http://localhost:9090/api/v1/targets | python -m json.tool | grep "health"
```

Expected: `"health": "up"` for the ragnarok-antifraude target.

If target shows `"down"`, check:
```bash
docker compose logs antifraude | tail -20
curl http://localhost:8081/actuator/health
```

- [ ] **Step 5: Verify metrics endpoint**

```bash
curl -s http://localhost:8081/actuator/prometheus | grep "fraud_"
```

Expected: metrics like `fraud_decisions_total` appear after at least one `/api/fraud/analyze` call. Send a test request first:

```bash
curl -s -X POST http://localhost:8081/api/fraud/analyze \
  -H "Content-Type: application/json" \
  -d '{"eventId":"test-001","eventType":"ITEM_TRADE","playerId":1,"ipAddress":"127.0.0.1","countryCode":"BR","occurredAt":"2026-01-01T00:00:00Z","payload":{"itemId":501,"itemUuid":"abc-123","zenysValue":100}}'
```

Then re-run the grep command. Expected: `fraud_decisions_total` line appears.

- [ ] **Step 6: Verify Grafana dashboard loads**

Open `http://localhost:3000` in browser. Login with admin/admin. Navigate to Dashboards → Anti-Fraud → Ragnarok Anti-Fraud Dashboard. All 4 panels must render without "No data" errors (after sending a few test requests in Step 5).

- [ ] **Step 7: Commit**

```bash
git add docker-compose.yml docker/ pom.xml src/main/resources/application.yml
git commit -m "feat(observability): add Prometheus + Grafana with fraud metrics dashboard"
```

Plan complete when Grafana dashboard shows live data from `fraud_decisions_total` metric.
