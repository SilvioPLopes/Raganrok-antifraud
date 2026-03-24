package com.ragnarok.antifraude.domain.port.out;

import com.ragnarok.antifraude.domain.model.FraudDecision;
import com.ragnarok.antifraude.domain.model.FraudEvent;

import java.time.Instant;
import java.util.List;

/**
 * Port de saída — persistência de auditoria.
 * Implementado por JpaAuditAdapter (infrastructure).
 * Chamado de forma ASSÍNCRONA — nunca no caminho crítico.
 */
public interface AuditRepository {

    void save(FraudEvent event, FraudDecision decision);

    List<AuditRecord> findByPlayerId(Long playerId, int limit);

    long countBlocksByPlayerSince(Long playerId, Instant since);

    record AuditRecord(
        String eventId,
        String eventType,
        Long playerId,
        String verdict,
        String triggeredRules,
        String reason,
        long processingTimeMs,
        Instant createdAt
    ) {}
}
