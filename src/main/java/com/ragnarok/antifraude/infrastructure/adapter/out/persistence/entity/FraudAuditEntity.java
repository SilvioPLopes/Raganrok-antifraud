package com.ragnarok.antifraude.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Audit log — append-only. Nunca editado, nunca deletado.
 * Tabela particionável por mês se o volume crescer.
 */
@Entity
@Table(name = "fraud_audit_log", indexes = {
    @Index(name = "idx_audit_player_id", columnList = "playerId"),
    @Index(name = "idx_audit_created_at", columnList = "createdAt"),
    @Index(name = "idx_audit_verdict", columnList = "verdict")
})
public class FraudAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String eventId;

    @Column(nullable = false, length = 32)
    private String eventType;

    @Column(nullable = false)
    private Long playerId;

    @Column(nullable = false, length = 16)
    private String verdict;

    @Column(length = 32)
    private String requiredAction;

    @Column(length = 16)
    private String riskLevel;

    @Column(length = 512)
    private String triggeredRules;

    @Column(length = 1024)
    private String reason;

    @Column(nullable = false)
    private long processingTimeMs;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    // ── Getters / Setters ───────────────────────────────────────────────────

    public Long getId()                { return id; }
    public String getEventId()         { return eventId; }
    public String getEventType()       { return eventType; }
    public Long getPlayerId()          { return playerId; }
    public String getVerdict()         { return verdict; }
    public String getRequiredAction()  { return requiredAction; }
    public String getRiskLevel()       { return riskLevel; }
    public String getTriggeredRules()  { return triggeredRules; }
    public String getReason()          { return reason; }
    public long getProcessingTimeMs()  { return processingTimeMs; }
    public Instant getCreatedAt()      { return createdAt; }

    public void setEventId(String eventId)               { this.eventId = eventId; }
    public void setEventType(String eventType)           { this.eventType = eventType; }
    public void setPlayerId(Long playerId)               { this.playerId = playerId; }
    public void setVerdict(String verdict)               { this.verdict = verdict; }
    public void setRequiredAction(String requiredAction) { this.requiredAction = requiredAction; }
    public void setRiskLevel(String riskLevel)           { this.riskLevel = riskLevel; }
    public void setTriggeredRules(String triggeredRules) { this.triggeredRules = triggeredRules; }
    public void setReason(String reason)                 { this.reason = reason; }
    public void setProcessingTimeMs(long processingTimeMs) { this.processingTimeMs = processingTimeMs; }
    public void setCreatedAt(Instant createdAt)          { this.createdAt = createdAt; }
}
