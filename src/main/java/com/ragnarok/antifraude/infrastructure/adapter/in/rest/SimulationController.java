package com.ragnarok.antifraude.infrastructure.adapter.in.rest;

import com.ragnarok.antifraude.infrastructure.adapter.in.rest.dto.SimulationResultResponse;
import com.ragnarok.antifraude.infrastructure.simulation.SimulationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/simulate")
public class SimulationController {

    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping("/{scenario}")
    public ResponseEntity<SimulationResultResponse> simulate(
            @PathVariable String scenario,
            @RequestParam(defaultValue = "50") int count) {
        var result = simulationService.simulate(scenario, count);
        return ResponseEntity.ok(SimulationResultResponse.from(result));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
