package com.ragnarok.antifraude.application.rule;

import com.ragnarok.antifraude.domain.model.FraudEvent;
import com.ragnarok.antifraude.domain.model.RequiredAction;
import com.ragnarok.antifraude.domain.model.RiskLevel;
import com.ragnarok.antifraude.domain.rule.FraudRule;
import com.ragnarok.antifraude.domain.rule.RuleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Regra 9 — Alerta de monopólio de mercado.
 * Detecta quando um jogador tenta comprar uma fatia absurda do estoque de um item.
 *   >80% do estoque → ALERT_ONLY
 *   >95% do estoque → FLAG_FOR_REVIEW
 * Dados vêm no payload — sem acesso a storage externo.
 */
@Component
public class MarketMonopolyRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(MarketMonopolyRule.class);
    private static final double ALERT_THRESHOLD = 0.80;
    private static final double FLAG_THRESHOLD = 0.95;

    @Override public String ruleId()         { return "MARKET_MONOPOLY"; }
    @Override public Set<String> eventTypes() { return Set.of("MARKET_PURCHASE"); }
    @Override public int priority()          { return 9; }

    @Override
    public RuleResult evaluate(FraudEvent event) {
        try {
            Long quantityRequested = event.payloadValue("quantityRequested", Long.class);
            Long totalInStock = event.payloadValue("totalInStock", Long.class);

            if (quantityRequested == null || totalInStock == null || totalInStock <= 0) {
                return RuleResult.approved(ruleId());
            }

            double ratio = (double) quantityRequested / totalInStock;
            Long itemId = event.payloadValue("itemId", Long.class);

            if (ratio >= FLAG_THRESHOLD) {
                return RuleResult.challenge(ruleId(), RequiredAction.FLAG_FOR_REVIEW, RiskLevel.HIGH,
                    String.format("Market monopoly attempt: %.0f%% of stock for item %d (%d/%d)",
                        ratio * 100, itemId, quantityRequested, totalInStock));
            }

            if (ratio >= ALERT_THRESHOLD) {
                return RuleResult.challenge(ruleId(), RequiredAction.ALERT_ONLY, RiskLevel.MEDIUM,
                    String.format("Large market purchase: %.0f%% of stock for item %d (%d/%d)",
                        ratio * 100, itemId, quantityRequested, totalInStock));
            }

            return RuleResult.approved(ruleId());
        } catch (Exception e) {
            log.warn("[{}] Error evaluating — fail-open: {}", ruleId(), e.getMessage());
            return RuleResult.approved(ruleId());
        }
    }
}
