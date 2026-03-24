package com.ragnarok.antifraude.domain.model;

import java.time.Instant;
import java.util.Map;

/**
 * Evento de fraude recebido do ragnarok-core.
 * Record imutável — thread-safe por definição.
 * Nenhum import de framework aqui.
 */
public record FraudEvent(
    String eventId,
    String eventType,
    Long playerId,
    String ipAddress,
    String countryCode,
    Instant occurredAt,
    Map<String, Object> payload
) {
    public FraudEvent {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("eventId is required");
        if (eventType == null || eventType.isBlank()) throw new IllegalArgumentException("eventType is required");
        if (playerId == null) throw new IllegalArgumentException("playerId is required");
        if (payload == null) payload = Map.of();
        if (ipAddress == null) ipAddress = "";
        if (countryCode == null) countryCode = "BR";
        if (occurredAt == null) occurredAt = Instant.now();
    }

    /** Helper para ler valor do payload com cast seguro. */
    @SuppressWarnings("unchecked")
    public <T> T payloadValue(String key, Class<T> type) {
        Object val = payload.get(key);
        if (val == null) return null;
        if (type.isInstance(val)) return (T) val;
        // Conversões comuns de JSON (Jackson deserializa números como Integer/Double)
        if (type == Long.class && val instanceof Number n) return (T) Long.valueOf(n.longValue());
        if (type == Double.class && val instanceof Number n) return (T) Double.valueOf(n.doubleValue());
        if (type == String.class) return (T) val.toString();
        if (type == Boolean.class && val instanceof Boolean) return (T) val;
        return null;
    }

    /** Shortcut: payloadValue com default. */
    public <T> T payloadOrDefault(String key, Class<T> type, T defaultValue) {
        T val = payloadValue(key, type);
        return val != null ? val : defaultValue;
    }
}
