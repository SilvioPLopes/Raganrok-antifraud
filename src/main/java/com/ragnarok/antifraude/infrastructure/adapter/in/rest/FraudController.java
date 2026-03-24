package com.ragnarok.antifraude.infrastructure.adapter.in.rest;

import com.ragnarok.antifraude.domain.port.in.FraudAnalysisUseCase;
import com.ragnarok.antifraude.infrastructure.adapter.in.rest.dto.FraudDecisionResponse;
import com.ragnarok.antifraude.infrastructure.adapter.in.rest.dto.FraudEventRequest;
import com.ragnarok.antifraude.infrastructure.adapter.in.rest.mapper.FraudEventMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/fraud")
public class FraudController {

    private final FraudAnalysisUseCase fraudAnalysis;

    public FraudController(FraudAnalysisUseCase fraudAnalysis) {
        this.fraudAnalysis = fraudAnalysis;
    }

    @PostMapping("/analyze")
    public ResponseEntity<FraudDecisionResponse> analyze(@Valid @RequestBody FraudEventRequest request) {
        var event = FraudEventMapper.toDomain(request);
        var decision = fraudAnalysis.analyze(event);
        return ResponseEntity.ok(FraudDecisionResponse.from(decision));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "ragnarok-antifraude"));
    }
}
