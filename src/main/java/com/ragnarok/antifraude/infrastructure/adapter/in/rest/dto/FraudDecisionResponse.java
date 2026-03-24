package com.ragnarok.antifraude.infrastructure.adapter.in.rest.dto;

import com.ragnarok.antifraude.domain.model.FraudDecision;

import java.util.List;

public record FraudDecisionResponse(
    String eventId,
    Long playerId,
    String verdict,
    String requiredAction,
    String riskLevel,
    List<String> triggeredRules,
    String reason,
    long processingTimeMs
) {
    public static FraudDecisionResponse from(FraudDecision d) {
        return new FraudDecisionResponse(
            d.eventId(),
            d.playerId(),
            d.verdict().name(),
            d.requiredAction().name(),
            d.riskLevel().name(),
            d.triggeredRules(),
            d.reason(),
            d.processingTimeMs()
        );
    }
}
