package com.ragnarok.antifraude.application.service;

import com.ragnarok.antifraude.domain.model.*;
import com.ragnarok.antifraude.domain.port.in.FraudAnalysisUseCase;
import com.ragnarok.antifraude.domain.rule.FraudRule;
import com.ragnarok.antifraude.domain.rule.RuleResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Motor antifraude — orquestra as 9 regras em PARALELO.
 *
 * Estratégia:
 * 1. Filtra regras aplicáveis pelo eventType
 * 2. Executa todas em paralelo com Virtual Threads
 * 3. Timeout hard de 50ms — regras lentas são ignoradas (fail-open)
 * 4. Combina resultados: pior veredicto vence
 * 5. Audit log é assíncrono (via AuditLogService)
 */
@Service
public class FraudAnalysisService implements FraudAnalysisUseCase {

    private static final Logger log = LoggerFactory.getLogger(FraudAnalysisService.class);

    private final long timeoutMs;
    private final List<FraudRule> rules;
    private final AuditLogService auditLogService;
    private final ExecutorService executor;
    private final MeterRegistry meterRegistry;

    public FraudAnalysisService(
            List<FraudRule> rules,
            AuditLogService auditLogService,
            MeterRegistry meterRegistry,
            @org.springframework.beans.factory.annotation.Value("${antifraude.rule.timeout.ms:50}") long timeoutMs) {
        this.rules = rules;
        this.auditLogService = auditLogService;
        this.meterRegistry = meterRegistry;
        this.timeoutMs = timeoutMs;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public FraudDecision analyze(FraudEvent event) {
        long start = System.currentTimeMillis();

        // 1. Filtra regras aplicáveis
        List<FraudRule> applicable = rules.stream()
            .filter(r -> r.eventTypes().contains(event.eventType()))
            .toList();

        if (applicable.isEmpty()) {
            long earlyElapsed = System.currentTimeMillis() - start;
            meterRegistry.counter("fraud.decisions.total",
                "verdict", Verdict.APPROVED.name(),
                "eventType", event.eventType()
            ).increment();
            meterRegistry.timer("fraud.processing.duration")
                .record(Duration.ofMillis(earlyElapsed));
            return FraudDecision.approved(event.eventId(), event.playerId(), earlyElapsed);
        }

        // 2. Executa em paralelo
        List<CompletableFuture<RuleResult>> futures = applicable.stream()
            .map(rule -> CompletableFuture.supplyAsync(() -> rule.evaluate(event), executor))
            .toList();

        // 3. Espera todas com timeout
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        try {
            all.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("Some rules timed out for event {} — fail-open", event.eventId());
        }

        // 4. Coleta resultados (regras que não retornaram a tempo → ignoradas)
        List<RuleResult> results = new ArrayList<>();
        for (CompletableFuture<RuleResult> future : futures) {
            if (future.isDone() && !future.isCompletedExceptionally()) {
                try {
                    results.add(future.getNow(null));
                } catch (Exception ignored) { }
            }
        }

        // 5. Combina: pior veredicto vence, maior prioridade em caso de empate
        long elapsed = System.currentTimeMillis() - start;
        FraudDecision decision = combineResults(event, results, elapsed);

        // Metrics
        meterRegistry.counter("fraud.decisions.total",
            "verdict", decision.verdict().name(),
            "eventType", event.eventType()
        ).increment();

        decision.triggeredRules().forEach(ruleId ->
            meterRegistry.counter("fraud.rule.triggered.total", "ruleId", ruleId).increment()
        );

        meterRegistry.timer("fraud.processing.duration")
            .record(Duration.ofMillis(elapsed));

        // 6. Audit assíncrono
        auditLogService.logAsync(event, decision);

        return decision;
    }

    private FraudDecision combineResults(FraudEvent event, List<RuleResult> results, long elapsed) {
        List<RuleResult> triggered = results.stream()
            .filter(RuleResult::isTriggered)
            .toList();

        if (triggered.isEmpty()) {
            return FraudDecision.approved(event.eventId(), event.playerId(), elapsed);
        }

        // Pior veredicto, maior risk level, ação mais severa
        RuleResult worst = triggered.stream()
            .max(Comparator
                .comparing((RuleResult r) -> r.verdict().ordinal())
                .thenComparing(r -> r.riskLevel().ordinal()))
            .orElseThrow();

        List<String> ruleIds = triggered.stream()
            .map(RuleResult::ruleId)
            .toList();

        return new FraudDecision(
            event.eventId(),
            event.playerId(),
            worst.verdict(),
            worst.requiredAction(),
            worst.riskLevel(),
            ruleIds,
            worst.reason(),
            elapsed,
            null
        );
    }
}
