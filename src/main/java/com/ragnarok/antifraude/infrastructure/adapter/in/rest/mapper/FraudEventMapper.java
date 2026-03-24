package com.ragnarok.antifraude.infrastructure.adapter.in.rest.mapper;

import com.ragnarok.antifraude.domain.model.FraudEvent;
import com.ragnarok.antifraude.infrastructure.adapter.in.rest.dto.FraudEventRequest;

/**
 * Converte DTOs de infraestrutura para objetos de domínio.
 * Sem framework — conversão manual e explícita.
 */
public final class FraudEventMapper {

    private FraudEventMapper() {}

    public static FraudEvent toDomain(FraudEventRequest req) {
        return new FraudEvent(
            req.eventId(),
            req.eventType(),
            req.playerId(),
            req.ipAddress(),
            req.countryCode(),
            req.occurredAt(),
            req.payload()
        );
    }
}
