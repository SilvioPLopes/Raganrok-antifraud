package com.ragnarok.antifraude.adapter.in.rest;

import com.ragnarok.antifraude.infrastructure.adapter.in.rest.SimulationController;
import com.ragnarok.antifraude.infrastructure.simulation.SimulationResult;
import com.ragnarok.antifraude.infrastructure.simulation.SimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SimulationController.class)
class SimulationControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean SimulationService simulationService;

    @Test
    void postSimulate_returnsOkWithResult() throws Exception {
        when(simulationService.simulate(any(), anyInt())).thenReturn(
            new SimulationResult("bot-attack", 10,
                Map.of("BLOCKED", 10L), Map.of("BOT_CLICK_SPEED", 10L), 45L)
        );

        mockMvc.perform(post("/api/simulate/bot-attack").param("count", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scenario").value("bot-attack"))
            .andExpect(jsonPath("$.eventsGenerated").value(10))
            .andExpect(jsonPath("$.verdictCounts.BLOCKED").value(10))
            .andExpect(jsonPath("$.durationMs").isNumber());
    }

    @Test
    void postSimulate_unknownScenario_returns400() throws Exception {
        when(simulationService.simulate(any(), anyInt()))
            .thenThrow(new IllegalArgumentException("Unknown scenario: bad-scenario"));

        mockMvc.perform(post("/api/simulate/bad-scenario"))
            .andExpect(status().isBadRequest());
    }
}
