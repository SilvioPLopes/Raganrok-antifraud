package com.ragnarok.antifraude.infrastructure.adapter.in.rest.dto;

import com.ragnarok.antifraude.infrastructure.simulation.SimulationResult;
import java.util.Map;

public record SimulationResultResponse(
    String scenario,
    int eventsGenerated,
    Map<String, Long> verdictCounts,
    Map<String, Long> triggeredRules,
    long durationMs
) {
    public static SimulationResultResponse from(SimulationResult result) {
        return new SimulationResultResponse(
            result.scenario(),
            result.eventsGenerated(),
            result.verdictCounts(),
            result.triggeredRules(),
            result.durationMs()
        );
    }
}
