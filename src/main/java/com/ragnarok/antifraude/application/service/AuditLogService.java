package com.ragnarok.antifraude.application.service;

import com.ragnarok.antifraude.domain.model.FraudDecision;
import com.ragnarok.antifraude.domain.model.FraudEvent;
import com.ragnarok.antifraude.domain.port.out.AuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Persiste audit log de forma ASSÍNCRONA.
 * Nunca está no caminho crítico de resposta.
 * Se falhar, loga e segue — nunca bloqueia o motor.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditRepository auditRepository;

    public AuditLogService(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Async("auditExecutor")
    public void logAsync(FraudEvent event, FraudDecision decision) {
        try {
            auditRepository.save(event, decision);
        } catch (Exception e) {
            log.error("Failed to persist audit log for event {}: {}", event.eventId(), e.getMessage());
            // Nunca propaga — o motor já respondeu
        }
    }
}
