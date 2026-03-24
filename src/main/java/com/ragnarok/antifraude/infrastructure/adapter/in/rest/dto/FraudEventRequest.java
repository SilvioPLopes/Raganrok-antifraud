package com.ragnarok.antifraude.infrastructure.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;

public record FraudEventRequest(
    @NotBlank String eventId,
    @NotBlank String eventType,
    @NotNull Long playerId,
    String ipAddress,
    String countryCode,
    Instant occurredAt,
    Map<String, Object> payload
) {}
