package com.ragnarok.antifraude.infrastructure.simulation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SimulationResultTest {

    @Test
    void simulationResult_holdsAllFields() {
        var result = new SimulationResult(
            "bot-attack",
            50,
            java.util.Map.of("APPROVED", 5L, "BLOCKED", 45L),
            java.util.Map.of("BOT_CLICK_SPEED", 45L),
            120L
        );

        assertThat(result.scenario()).isEqualTo("bot-attack");
        assertThat(result.eventsGenerated()).isEqualTo(50);
        assertThat(result.verdictCounts().get("BLOCKED")).isEqualTo(45L);
        assertThat(result.triggeredRules().get("BOT_CLICK_SPEED")).isEqualTo(45L);
        assertThat(result.durationMs()).isEqualTo(120L);
    }
}
