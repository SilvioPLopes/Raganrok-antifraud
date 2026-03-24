package com.ragnarok.antifraude.infrastructure.adapter.in.rest;

import com.ragnarok.antifraude.domain.port.out.AuditRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Dashboard para game masters investigarem jogadores.
 */
@RestController
@RequestMapping("/api/fraud/admin")
public class AdminDashboardController {

    private final AuditRepository auditRepo;

    public AdminDashboardController(AuditRepository auditRepo) {
        this.auditRepo = auditRepo;
    }

    /** Histórico completo de decisões de um jogador. */
    @GetMapping("/player/{id}/history")
    public ResponseEntity<List<AuditRepository.AuditRecord>> history(
            @PathVariable Long id,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(auditRepo.findByPlayerId(id, limit));
    }

    /** Contagem de bloqueios por janela de tempo. */
    @GetMapping("/player/{id}/blocks")
    public ResponseEntity<Map<String, Object>> blocks(
            @PathVariable Long id,
            @RequestParam(defaultValue = "24") int hours) {
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);
        long count = auditRepo.countBlocksByPlayerSince(id, since);
        return ResponseEntity.ok(Map.of("playerId", id, "hours", hours, "blockCount", count));
    }
}
