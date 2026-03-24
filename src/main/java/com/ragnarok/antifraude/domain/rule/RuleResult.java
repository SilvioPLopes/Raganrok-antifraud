package com.ragnarok.antifraude.domain.rule;

import com.ragnarok.antifraude.domain.model.RequiredAction;
import com.ragnarok.antifraude.domain.model.RiskLevel;
import com.ragnarok.antifraude.domain.model.Verdict;

/**
 * Resultado de uma regra individual.
 * Cada regra retorna um RuleResult; o motor combina todos para gerar a FraudDecision final.
 */
public record RuleResult(
    String ruleId,
    Verdict verdict,
    RequiredAction requiredAction,
    RiskLevel riskLevel,
    String reason
) {
    /** Regra aprovou — sem problemas encontrados. */
    public static RuleResult approved(String ruleId) {
        return new RuleResult(ruleId, Verdict.APPROVED, RequiredAction.NONE, RiskLevel.LOW, null);
    }

    /** Regra bloqueou — ação deve ser cancelada. */
    public static RuleResult blocked(String ruleId, RequiredAction action, RiskLevel risk, String reason) {
        return new RuleResult(ruleId, Verdict.BLOCKED, action, risk, reason);
    }

    /** Regra desafiou — precisa de verificação adicional (captcha, etc). */
    public static RuleResult challenge(String ruleId, RequiredAction action, RiskLevel risk, String reason) {
        return new RuleResult(ruleId, Verdict.CHALLENGE, action, risk, reason);
    }

    public boolean isTriggered() {
        return verdict != Verdict.APPROVED;
    }
}
