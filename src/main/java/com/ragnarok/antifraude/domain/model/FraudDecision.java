package com.ragnarok.antifraude.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * Decisão final do motor antifraude.
 * Record imutável — thread-safe. Construído pelo FraudAnalysisService.
 */
public record FraudDecision(
    String eventId,
    Long playerId,
    Verdict verdict,
    RequiredAction requiredAction,
    RiskLevel riskLevel,
    List<String> triggeredRules,
    String reason,
    long processingTimeMs,
    Instant decidedAt
) {
    public FraudDecision {
        if (triggeredRules == null) triggeredRules = List.of();
        if (decidedAt == null) decidedAt = Instant.now();
    }

    /** Factory: aprovação limpa (nenhuma regra disparou). */
    public static FraudDecision approved(String eventId, Long playerId, long processingTimeMs) {
        return new FraudDecision(
            eventId, playerId, Verdict.APPROVED, RequiredAction.NONE,
            RiskLevel.LOW, List.of(), "All rules passed", processingTimeMs, Instant.now()
        );
    }

    /** Factory: timeout do motor — fail-open. */
    public static FraudDecision timeout(String eventId, Long playerId) {
        return new FraudDecision(
            eventId, playerId, Verdict.APPROVED, RequiredAction.NONE,
            RiskLevel.LOW, List.of(), "Engine timeout — fail-open", 50, Instant.now()
        );
    }

    public boolean isBlocked()   { return verdict == Verdict.BLOCKED; }
    public boolean isChallenge() { return verdict == Verdict.CHALLENGE; }
    public boolean isApproved()  { return verdict == Verdict.APPROVED; }
}
