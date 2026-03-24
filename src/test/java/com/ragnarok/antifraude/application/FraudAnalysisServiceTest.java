package com.ragnarok.antifraude.application;

import com.ragnarok.antifraude.application.service.AuditLogService;
import com.ragnarok.antifraude.application.service.FraudAnalysisService;
import com.ragnarok.antifraude.domain.model.*;
import com.ragnarok.antifraude.domain.rule.FraudRule;
import com.ragnarok.antifraude.domain.rule.RuleResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class FraudAnalysisServiceTest {

    @Mock AuditLogService auditLogService;

    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private FraudEvent event(String eventType) {
        return new FraudEvent("e1", eventType, 1L, "127.0.0.1", "BR", Instant.now(), Map.of());
    }

    @Test
    @DisplayName("Nenhuma regra aplicável → APPROVED")
    void noApplicableRules_approved() {
        var service = new FraudAnalysisService(List.of(), auditLogService, meterRegistry);
        var result = service.analyze(event("UNKNOWN_EVENT"));
        assertEquals(Verdict.APPROVED, result.verdict());
    }

    @Test
    @DisplayName("Todas as regras aprovam → APPROVED")
    void allRulesApprove_approved() {
        FraudRule rule1 = stubRule("R1", "ITEM_TRADE", RuleResult.approved("R1"));
        FraudRule rule2 = stubRule("R2", "ITEM_TRADE", RuleResult.approved("R2"));

        var service = new FraudAnalysisService(List.of(rule1, rule2), auditLogService, meterRegistry);
        var result = service.analyze(event("ITEM_TRADE"));
        assertEquals(Verdict.APPROVED, result.verdict());
    }

    @Test
    @DisplayName("Uma regra bloqueia → BLOCKED (pior veredicto vence)")
    void oneRuleBlocks_blocked() {
        FraudRule ruleOk = stubRule("R1", "ITEM_TRADE", RuleResult.approved("R1"));
        FraudRule ruleBlock = stubRule("R2", "ITEM_TRADE",
            RuleResult.blocked("R2", RequiredAction.CANCEL_ACTION, RiskLevel.HIGH, "blocked"));

        var service = new FraudAnalysisService(List.of(ruleOk, ruleBlock), auditLogService, meterRegistry);
        var result = service.analyze(event("ITEM_TRADE"));
        assertEquals(Verdict.BLOCKED, result.verdict());
        assertTrue(result.triggeredRules().contains("R2"));
    }

    @Test
    @DisplayName("Regra lenta (>50ms) → ignorada, retorna APPROVED")
    void slowRule_ignored() {
        FraudRule slowRule = new FraudRule() {
            public String ruleId() { return "SLOW"; }
            public Set<String> eventTypes() { return Set.of("ITEM_TRADE"); }
            public int priority() { return 1; }
            public RuleResult evaluate(FraudEvent event) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                return RuleResult.blocked(ruleId(), RequiredAction.CANCEL_ACTION, RiskLevel.CRITICAL, "too slow");
            }
        };

        var service = new FraudAnalysisService(List.of(slowRule), auditLogService, meterRegistry);
        var result = service.analyze(event("ITEM_TRADE"));
        // Regra não retornou a tempo → ignorada → APPROVED
        assertEquals(Verdict.APPROVED, result.verdict());
    }

    @Test
    @DisplayName("Regra com exceção → fail-open, APPROVED")
    void ruleException_failOpen() {
        FraudRule crashingRule = new FraudRule() {
            public String ruleId() { return "CRASH"; }
            public Set<String> eventTypes() { return Set.of("ITEM_TRADE"); }
            public int priority() { return 1; }
            public RuleResult evaluate(FraudEvent event) {
                throw new RuntimeException("kaboom");
            }
        };

        var service = new FraudAnalysisService(List.of(crashingRule), auditLogService, meterRegistry);
        var result = service.analyze(event("ITEM_TRADE"));
        assertEquals(Verdict.APPROVED, result.verdict());
    }

    @Test
    @DisplayName("analyze registra métrica fraud.decisions.total")
    void analyze_recordsDecisionCounterMetric() {
        FraudRule rule = stubRule("R1", "ITEM_TRADE", RuleResult.approved("R1"));
        var service = new FraudAnalysisService(List.of(rule), auditLogService, meterRegistry);

        service.analyze(event("ITEM_TRADE"));

        Counter counter = meterRegistry.find("fraud.decisions.total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThan(0);
    }

    @Test
    @DisplayName("analyze registra métrica fraud.processing.duration")
    void analyze_recordsProcessingDurationMetric() {
        FraudRule rule = stubRule("R1", "ITEM_TRADE", RuleResult.approved("R1"));
        var service = new FraudAnalysisService(List.of(rule), auditLogService, meterRegistry);

        service.analyze(event("ITEM_TRADE"));

        assertThat(meterRegistry.find("fraud.processing.duration").timer()).isNotNull();
        assertThat(meterRegistry.find("fraud.processing.duration").timer().count()).isGreaterThan(0);
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private FraudRule stubRule(String id, String eventType, RuleResult result) {
        return new FraudRule() {
            public String ruleId() { return id; }
            public Set<String> eventTypes() { return Set.of(eventType); }
            public int priority() { return 1; }
            public RuleResult evaluate(FraudEvent event) { return result; }
        };
    }
}
