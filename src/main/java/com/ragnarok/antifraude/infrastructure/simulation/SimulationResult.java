package com.ragnarok.antifraude.infrastructure.simulation;

import java.util.Map;

public record SimulationResult(
    String scenario,
    int eventsGenerated,
    Map<String, Long> verdictCounts,
    Map<String, Long> triggeredRules,
    long durationMs
) {}
