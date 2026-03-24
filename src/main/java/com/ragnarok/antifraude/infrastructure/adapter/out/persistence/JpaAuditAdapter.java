package com.ragnarok.antifraude.infrastructure.adapter.out.persistence;

import com.ragnarok.antifraude.domain.model.FraudDecision;
import com.ragnarok.antifraude.domain.model.FraudEvent;
import com.ragnarok.antifraude.domain.port.out.AuditRepository;
import com.ragnarok.antifraude.infrastructure.adapter.out.persistence.entity.FraudAuditEntity;
import com.ragnarok.antifraude.infrastructure.adapter.out.persistence.repository.FraudAuditJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class JpaAuditAdapter implements AuditRepository {

    private final FraudAuditJpaRepository jpaRepo;

    public JpaAuditAdapter(FraudAuditJpaRepository jpaRepo) {
        this.jpaRepo = jpaRepo;
    }

    @Override
    public void save(FraudEvent event, FraudDecision decision) {
        var entity = new FraudAuditEntity();
        entity.setEventId(event.eventId());
        entity.setEventType(event.eventType());
        entity.setPlayerId(event.playerId());
        entity.setVerdict(decision.verdict().name());
        entity.setRequiredAction(decision.requiredAction().name());
        entity.setRiskLevel(decision.riskLevel().name());
        entity.setTriggeredRules(String.join(",", decision.triggeredRules()));
        entity.setReason(decision.reason());
        entity.setProcessingTimeMs(decision.processingTimeMs());
        entity.setCreatedAt(decision.decidedAt());
        jpaRepo.save(entity);
    }

    @Override
    public List<AuditRecord> findByPlayerId(Long playerId, int limit) {
        return jpaRepo.findByPlayerIdOrderByCreatedAtDesc(playerId, PageRequest.of(0, limit))
            .stream()
            .map(e -> new AuditRecord(
                e.getEventId(), e.getEventType(), e.getPlayerId(),
                e.getVerdict(), e.getTriggeredRules(), e.getReason(),
                e.getProcessingTimeMs(), e.getCreatedAt()))
            .toList();
    }

    @Override
    public long countBlocksByPlayerSince(Long playerId, Instant since) {
        return jpaRepo.countBlocksByPlayerSince(playerId, since);
    }
}
