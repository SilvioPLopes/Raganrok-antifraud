package com.ragnarok.antifraude.domain.port.in;

import com.ragnarok.antifraude.domain.model.FraudDecision;
import com.ragnarok.antifraude.domain.model.FraudEvent;

/**
 * Port de entrada — use case principal.
 * Implementado por FraudAnalysisService (application layer).
 * Chamado pelo FraudController (infrastructure layer).
 */
public interface FraudAnalysisUseCase {

    FraudDecision analyze(FraudEvent event);
}
