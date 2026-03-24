package com.ragnarok.antifraude.application.rule;

import com.ragnarok.antifraude.domain.model.FraudEvent;
import com.ragnarok.antifraude.domain.model.RequiredAction;
import com.ragnarok.antifraude.domain.model.RiskLevel;
import com.ragnarok.antifraude.domain.port.out.PlayerActivityRepository;
import com.ragnarok.antifraude.domain.rule.FraudRule;
import com.ragnarok.antifraude.domain.rule.RuleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Regra 6 — Segurança de transações de cash (ROPs).
 * Bloqueia compra de cash se o CPF/CNPJ do billing não bate com o titular da conta.
 * Dados do titular estão no Redis (sincronizados via /state/player/{id}/registration).
 */
@Component
public class CashSecurityRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(CashSecurityRule.class);

    private final PlayerActivityRepository playerRepo;

    public CashSecurityRule(PlayerActivityRepository playerRepo) {
        this.playerRepo = playerRepo;
    }

    @Override public String ruleId()         { return "CASH_SECURITY"; }
    @Override public Set<String> eventTypes() { return Set.of("CASH_PURCHASE"); }
    @Override public int priority()          { return 6; }

    @Override
    public RuleResult evaluate(FraudEvent event) {
        try {
            String billingDocument = event.payloadValue("billingDocument", String.class);
            String billingName = event.payloadValue("billingName", String.class);

            if (billingDocument == null || billingDocument.isBlank()) {
                return RuleResult.blocked(ruleId(), RequiredAction.CANCEL_ACTION, RiskLevel.HIGH,
                    "Cash purchase without billing document");
            }

            // Busca documento cadastrado do jogador
            var registeredDoc = playerRepo.getPlayerDocument(event.playerId());
            if (registeredDoc.isEmpty()) {
                return RuleResult.challenge(ruleId(), RequiredAction.FLAG_FOR_REVIEW, RiskLevel.MEDIUM,
                    "Cash purchase — no registered document on file for player " + event.playerId());
            }

            // Normaliza para comparação (remove pontuação)
            String normalizedBilling = normalizeDocument(billingDocument);
            String normalizedRegistered = normalizeDocument(registeredDoc.get());

            if (!normalizedBilling.equals(normalizedRegistered)) {
                return RuleResult.blocked(ruleId(), RequiredAction.CANCEL_ACTION, RiskLevel.CRITICAL,
                    "Billing document mismatch — possible stolen payment method");
            }

            return RuleResult.approved(ruleId());
        } catch (Exception e) {
            log.warn("[{}] Error evaluating — fail-open: {}", ruleId(), e.getMessage());
            return RuleResult.approved(ruleId());
        }
    }

    private String normalizeDocument(String doc) {
        return doc.replaceAll("[^0-9]", "");
    }
}
