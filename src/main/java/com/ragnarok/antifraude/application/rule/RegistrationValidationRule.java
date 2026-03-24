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
 * Regra 5 — Validação de cadastro inicial.
 * Bloqueia login se email não verificado ou menor de idade.
 * Dados vêm direto no payload — sem acesso a storage.
 */
@Component
public class RegistrationValidationRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(RegistrationValidationRule.class);

    @Override public String ruleId()         { return "REGISTRATION_VALIDATION"; }
    @Override public Set<String> eventTypes() { return Set.of("SESSION_LOGIN", "ACCOUNT_REGISTRATION"); }
    @Override public int priority()          { return 5; }

    @Override
    public RuleResult evaluate(FraudEvent event) {
        try {
            Boolean emailVerified = event.payloadValue("emailVerified", Boolean.class);
            Boolean ageVerified = event.payloadValue("ageVerified", Boolean.class);

            if (emailVerified != null && !emailVerified) {
                return RuleResult.blocked(ruleId(), RequiredAction.CANCEL_ACTION, RiskLevel.MEDIUM,
                    "Email not verified — login denied");
            }

            if (ageVerified != null && !ageVerified) {
                return RuleResult.blocked(ruleId(), RequiredAction.CANCEL_ACTION, RiskLevel.MEDIUM,
                    "Age verification failed — underage player");
            }

            return RuleResult.approved(ruleId());
        } catch (Exception e) {
            log.warn("[{}] Error evaluating — fail-open: {}", ruleId(), e.getMessage());
            return RuleResult.approved(ruleId());
        }
    }
}
