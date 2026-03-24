package com.ragnarok.antifraude.infrastructure.simulation;

import com.ragnarok.antifraude.domain.model.FraudDecision;
import com.ragnarok.antifraude.domain.model.RequiredAction;
import com.ragnarok.antifraude.domain.model.RiskLevel;
import com.ragnarok.antifraude.domain.model.Verdict;
import com.ragnarok.antifraude.domain.port.in.FraudAnalysisUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.HashOperations;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationServiceTest {

    @Mock FraudAnalysisUseCase fraudAnalysisUseCase;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @SuppressWarnings("rawtypes")
    @Mock HashOperations hashOps;

    SimulationService simulationService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOps);
        simulationService = new SimulationService(fraudAnalysisUseCase, redisTemplate);
    }

    @Test
    void simulate_botAttack_returnsBlockedVerdicts() {
        FraudDecision blocked = new FraudDecision(
            "evt-001", 900_000L, Verdict.BLOCKED, RequiredAction.CANCEL_ACTION,
            RiskLevel.HIGH, List.of("BOT_CLICK_SPEED"), "Simulated block", 10L, Instant.now()
        );
        when(fraudAnalysisUseCase.analyze(any())).thenReturn(blocked);

        SimulationResult result = simulationService.simulate("bot-attack", 10);

        assertThat(result.scenario()).isEqualTo("bot-attack");
        assertThat(result.eventsGenerated()).isEqualTo(10);
        assertThat(result.verdictCounts().getOrDefault("BLOCKED", 0L)).isGreaterThan(0);
    }

    @Test
    void simulate_normalTraffic_returnsApprovedVerdicts() {
        FraudDecision approved = new FraudDecision(
            "evt-002", 900_000L, Verdict.APPROVED, RequiredAction.NONE,
            RiskLevel.LOW, List.of(), "All rules passed", 5L, Instant.now()
        );
        when(fraudAnalysisUseCase.analyze(any())).thenReturn(approved);

        SimulationResult result = simulationService.simulate("normal-traffic", 20);

        assertThat(result.eventsGenerated()).isEqualTo(20);
        assertThat(result.verdictCounts().getOrDefault("APPROVED", 0L)).isEqualTo(20L);
    }

    @Test
    void simulate_unknownScenario_throwsIllegalArgument() {
        assertThatThrownBy(() -> simulationService.simulate("unknown-scenario", 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown-scenario");
    }
}
