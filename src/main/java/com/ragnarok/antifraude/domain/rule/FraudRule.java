package com.ragnarok.antifraude.domain.rule;

import com.ragnarok.antifraude.domain.model.FraudEvent;

import java.util.Set;

/**
 * Contrato de uma regra de fraude.
 *
 * Cada implementação:
 * - É stateless (todo estado vem de Redis/PostgreSQL via ports)
 * - Tem try/catch interno com fail-open
 * - Retorna em < 10ms (dados quentes em Redis)
 * - É thread-safe (será chamada em paralelo)
 */
public interface FraudRule {

    /** Identificador único. Ex: "ANTI_DUPE", "BOT_CLICK_SPEED". */
    String ruleId();

    /** EventTypes que esta regra avalia. Ex: Set.of("ITEM_TRADE"). */
    Set<String> eventTypes();

    /** Prioridade (menor = maior prioridade). Usado para desempate. */
    int priority();

    /**
     * Avalia o evento. DEVE ter try/catch interno:
     * se falhar, retorna RuleResult.approved(ruleId()) — fail-open.
     */
    RuleResult evaluate(FraudEvent event);
}
