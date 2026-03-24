package com.ragnarok.antifraude.application.rule;

import com.ragnarok.antifraude.domain.model.FraudEvent;
import com.ragnarok.antifraude.domain.model.RequiredAction;
import com.ragnarok.antifraude.domain.model.RiskLevel;
import com.ragnarok.antifraude.domain.port.out.TransactionRepository;
import com.ragnarok.antifraude.domain.rule.FraudRule;
import com.ragnarok.antifraude.domain.rule.RuleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Regra 2 — Transferências desproporcionais.
 * Compara zenysValue com o preço de mercado do item (cache Redis).
 * Ratios: >100x → FLAG_FOR_REVIEW, >1000x → CANCEL_ACTION, >10000x → CANCEL + CRITICAL.
 */
@Component
public class DisproportionateTransferRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(DisproportionateTransferRule.class);

    private final TransactionRepository transactionRepo;

    public DisproportionateTransferRule(TransactionRepository transactionRepo) {
        this.transactionRepo = transactionRepo;
    }

    @Override public String ruleId()         { return "DISPROPORTIONATE_TRANSFER"; }
    @Override public Set<String> eventTypes() { return Set.of("ITEM_TRADE"); }
    @Override public int priority()          { return 2; }

    @Override
    public RuleResult evaluate(FraudEvent event) {
        try {
            Long itemId = event.payloadValue("itemId", Long.class);
            Long zenysValue = event.payloadValue("zenysValue", Long.class);
            if (itemId == null || zenysValue == null || zenysValue <= 0) {
                return RuleResult.approved(ruleId());
            }

            var marketPrice = transactionRepo.getMarketPrice(itemId);
            if (marketPrice.isEmpty() || marketPrice.get() <= 0) {
                return RuleResult.approved(ruleId()); // Preço desconhecido → não bloqueia
            }

            double ratio = (double) zenysValue / marketPrice.get();

            if (ratio > 10_000) {
                return RuleResult.blocked(ruleId(), RequiredAction.CANCEL_ACTION, RiskLevel.CRITICAL,
                    String.format("Zeny transfer %.0fx above market price (item %d)", ratio, itemId));
            }
            if (ratio > 1_000) {
                return RuleResult.blocked(ruleId(), RequiredAction.CANCEL_ACTION, RiskLevel.HIGH,
                    String.format("Zeny transfer %.0fx above market price (item %d)", ratio, itemId));
            }
            if (ratio > 100) {
                return RuleResult.challenge(ruleId(), RequiredAction.FLAG_FOR_REVIEW, RiskLevel.MEDIUM,
                    String.format("Zeny transfer %.0fx above market price (item %d)", ratio, itemId));
            }

            return RuleResult.approved(ruleId());
        } catch (Exception e) {
            log.warn("[{}] Error evaluating — fail-open: {}", ruleId(), e.getMessage());
            return RuleResult.approved(ruleId());
        }
    }
}
