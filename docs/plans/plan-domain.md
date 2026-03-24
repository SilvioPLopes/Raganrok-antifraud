# Domain Layer Validation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify that the domain layer is architecturally pure (zero framework imports) and all 9 fraud rules are correctly implemented with fail-open behavior and complete unit test coverage.

**Architecture:** Read-only verification plan. No new code is written. All source files already exist. Tasks grep for violations, read interfaces and implementations for contract compliance, and run existing test suites to confirm correctness.

**Tech Stack:** Java 21, JUnit 5, Maven Wrapper (`./mvnw`)

**Spec:** `docs/superpowers/specs/2026-03-23-ragnarok-antifraude-design.md`

---

## File Map (read-only)

```
src/main/java/com/ragnarok/antifraude/
  domain/
    model/         FraudEvent.java, FraudDecision.java, Verdict.java, RequiredAction.java, RiskLevel.java
    rule/          FraudRule.java, RuleResult.java
    port/in/       FraudAnalysisUseCase.java
    port/out/      TransactionRepository.java, PlayerActivityRepository.java, AuditRepository.java
  application/
    rule/          AntiDupeRule.java, DisproportionateTransferRule.java, BotFarmTimeRule.java,
                   BotClickSpeedRule.java, RegistrationValidationRule.java, CashSecurityRule.java,
                   InstanceCooldownRule.java, ImpossibleTravelRule.java, MarketMonopolyRule.java

src/test/java/com/ragnarok/antifraude/
  domain/rule/     FraudRulesUnitTest.java
  application/     FraudAnalysisServiceTest.java
```

---

### Task 1: Assert domain layer has zero framework imports

**Files:**
- Read: `src/main/java/com/ragnarok/antifraude/domain/` (all files)

- [ ] **Step 1: Grep for Spring imports in domain package**

```bash
grep -r "import org.springframework" src/main/java/com/ragnarok/antifraude/domain/
```

Expected: no output (zero matches).

- [ ] **Step 2: Grep for Redis imports in domain package**

```bash
grep -r "import redis\|import org.springframework.data.redis" src/main/java/com/ragnarok/antifraude/domain/
```

Expected: no output.

- [ ] **Step 3: Grep for JPA imports in domain package**

```bash
grep -r "import jakarta.persistence\|import javax.persistence" src/main/java/com/ragnarok/antifraude/domain/
```

Expected: no output.

- [ ] **Step 4: If any grep returns results**

Read each flagged file. Remove the offending import. The domain layer must depend on nothing but the Java standard library.

---

### Task 2: Validate FraudRule interface contract

**Files:**
- Read: `src/main/java/com/ragnarok/antifraude/domain/rule/FraudRule.java`
- Read: `src/main/java/com/ragnarok/antifraude/domain/rule/RuleResult.java`

- [ ] **Step 1: Read FraudRule interface**

```bash
# Read the file via Read tool
# src/main/java/com/ragnarok/antifraude/domain/rule/FraudRule.java
```

Verify the interface declares exactly these methods:
- `String ruleId()` — unique identifier, e.g., `"ANTI_DUPE"`
- `List<String> eventTypes()` — event types this rule evaluates (e.g., `["ITEM_TRADE"]`)
- `int priority()` — precedence when multiple rules trigger
- `RuleResult evaluate(FraudEvent event)` — returns a result, never throws

- [ ] **Step 2: Read RuleResult record**

Verify `RuleResult` has:
- Fields: `String ruleId`, `Verdict verdict`, `RequiredAction requiredAction`, `RiskLevel riskLevel`, `String reason`
- Static factory: `RuleResult.approved()` — returns an APPROVED result
- Method `boolean isTriggered()` — returns true if verdict is not APPROVED

- [ ] **Step 3: If any method is missing**

Add the missing method to the interface or record. Do not change existing method signatures.

---

### Task 3: Validate all 9 rules have fail-open try/catch

**Files:**
- Read: each file in `src/main/java/com/ragnarok/antifraude/application/rule/`

- [ ] **Step 1: Read each rule implementation**

For each of the 9 rule classes, verify the `evaluate(FraudEvent event)` method:
1. Has a `try { ... } catch (Exception e) { return RuleResult.approved(); }` block wrapping the entire logic
2. The catch block does NOT rethrow
3. The catch block returns `RuleResult.approved()` (fail-open — if rule crashes, treat as approved)

Rules to check (file → expected ruleId):
- `AntiDupeRule.java` → `"ANTI_DUPE"`
- `DisproportionateTransferRule.java` → `"DISPROPORTIONATE_TRANSFER"`
- `BotFarmTimeRule.java` → `"BOT_FARM_TIME"`
- `BotClickSpeedRule.java` → `"BOT_CLICK_SPEED"`
- `RegistrationValidationRule.java` → `"REGISTRATION_VALIDATION"`
- `CashSecurityRule.java` → `"CASH_SECURITY"`
- `InstanceCooldownRule.java` → `"INSTANCE_COOLDOWN"`
- `ImpossibleTravelRule.java` → `"IMPOSSIBLE_TRAVEL"`
- `MarketMonopolyRule.java` → `"MARKET_MONOPOLY"`

- [ ] **Step 2: If any rule is missing the try/catch**

Wrap the rule's evaluate logic in try/catch:
```java
@Override
public RuleResult evaluate(FraudEvent event) {
    try {
        // existing logic here
    } catch (Exception e) {
        log.warn("[{}] fail-open on exception: {}", ruleId(), e.getMessage());
        return RuleResult.approved();
    }
}
```

---

### Task 4: Run FraudRulesUnitTest

**Files:**
- Read: `src/test/java/com/ragnarok/antifraude/domain/rule/FraudRulesUnitTest.java`

- [ ] **Step 1: Read the test file**

Verify the test covers for each rule:
- Approval scenario (valid data → APPROVED)
- Block scenario (data that triggers the rule → BLOCKED or CHALLENGE)
- Threshold scenario (boundary values — if the rule has thresholds like 100x/1000x)
- Fail-open scenario (rule throws exception → returns APPROVED)

- [ ] **Step 2: Run the test**

```bash
./mvnw test -Dtest="FraudRulesUnitTest" -Djacoco.skip=true -q
```

Expected output:
```
[INFO] Tests run: N, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 3: If tests fail**

Read the failing test output. Identify which rule and which scenario failed. Read the corresponding rule implementation. Fix the implementation (not the test) to match the expected behavior documented in the spec's rules table.

---

### Task 5: Run FraudAnalysisServiceTest

**Files:**
- Read: `src/test/java/com/ragnarok/antifraude/application/FraudAnalysisServiceTest.java`

- [ ] **Step 1: Read the test file**

Verify the test covers:
- Parallel execution: multiple rules execute concurrently (timing assertions)
- 50ms timeout: a rule that sleeps > 50ms is ignored (fail-open), not waited on
- Worst-verdict-wins: BLOCKED > CHALLENGE > APPROVED when multiple rules trigger
- Empty rules: if no rules apply to the eventType, returns APPROVED immediately

- [ ] **Step 2: Run the test**

```bash
./mvnw test -Dtest="FraudAnalysisServiceTest" -Djacoco.skip=true -q
```

Expected output:
```
[INFO] Tests run: N, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 3: If tests fail**

Read the failure message. Fix the `FraudAnalysisService` implementation to match. Do not modify tests.

- [ ] **Step 4: Commit verified domain layer**

```bash
git add -A
git commit -m "verify: domain layer pure, all 9 rules fail-open, unit tests passing"
```

---

### Task 6: Final verification summary

- [ ] **Step 1: Run both test suites together**

```bash
./mvnw test -Dtest="FraudRulesUnitTest,FraudAnalysisServiceTest" -Djacoco.skip=true -q
```

Expected: BUILD SUCCESS, zero failures.

- [ ] **Step 2: Confirm domain purity**

```bash
grep -r "import org.springframework\|import redis\|import jakarta" src/main/java/com/ragnarok/antifraude/domain/
```

Expected: no output.

Plan complete when both steps above produce the expected output.
